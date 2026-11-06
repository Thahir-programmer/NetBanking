package com.thahirudeen.netbanking.concurrency;

import com.thahirudeen.netbanking.common.InsufficientFundsException;
import com.thahirudeen.netbanking.support.AbstractIntegrationTest;
import com.thahirudeen.netbanking.transaction.TransactionRepository;
import com.thahirudeen.netbanking.transaction.TransactionType;
import com.thahirudeen.netbanking.transaction.TransferResponse;
import com.thahirudeen.netbanking.transaction.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These tests only mean something on a real Postgres: they rely on row locks (FOR UPDATE),
 * the @Version column and the unique constraint. Each one hammers the service layer from many
 * threads released at the same instant, then checks that money was neither created nor lost.
 */
class ConcurrencyTest extends AbstractIntegrationTest {

    @Autowired TransferService transferService;
    @Autowired TransactionRepository transactionRepository;

    private static List<String> failures(List<? extends Outcome<?>> results) {
        return results.stream().filter(r -> !r.succeeded()).map(r -> r.error().toString()).toList();
    }

    private static long successes(List<? extends Outcome<?>> results) {
        return results.stream().filter(Outcome::succeeded).count();
    }

    private long transferCount(TestUser user) {
        return transactionRepository.findHistory(user.accountId(), PageRequest.of(0, 500)).stream()
                .filter(t -> t.getType() == TransactionType.TRANSFER)
                .count();
    }

    @Test
    void opposingTransfersNeverDeadlockAndConserveMoney() throws Exception {
        TestUser a = newUser("1000.00");
        TestUser b = newUser("1000.00");
        String run = UUID.randomUUID().toString();

        // 20 transfers A->B and 20 transfers B->A, all at once: the classic deadlock setup
        List<Outcome<TransferResponse>> results = runConcurrently(40, i -> () -> i % 2 == 0
                ? transferService.transfer(a.userId(), b.accountNumber(), new BigDecimal("10.00"), "opp-" + run + "-" + i)
                : transferService.transfer(b.userId(), a.accountNumber(), new BigDecimal("10.00"), "opp-" + run + "-" + i));

        assertThat(failures(results)).as("every transfer should succeed, none deadlock or conflict").isEmpty();
        assertThat(balanceOf(a)).isEqualByComparingTo("1000.00"); // 20 out, 20 in
        assertThat(balanceOf(b)).isEqualByComparingTo("1000.00");
        assertThat(transferCount(a)).isEqualTo(40);
    }

    @Test
    void manyOneWayTransfersAllLandExactly() throws Exception {
        TestUser a = newUser("1000.00");
        TestUser b = newUser("0.00");
        String run = UUID.randomUUID().toString();

        List<Outcome<TransferResponse>> results = runConcurrently(30, i -> () ->
                transferService.transfer(a.userId(), b.accountNumber(), new BigDecimal("7.50"), "one-" + run + "-" + i));

        assertThat(failures(results)).isEmpty();
        assertThat(balanceOf(a)).isEqualByComparingTo("775.00"); // 1000 - 30 * 7.50
        assertThat(balanceOf(b)).isEqualByComparingTo("225.00");
    }

    @Test
    void theSameIdempotencyKeySentManyTimesAtOnceMovesMoneyExactlyOnce() throws Exception {
        TestUser a = newUser("500.00");
        TestUser b = newUser("0.00");

        List<Outcome<TransferResponse>> results = runConcurrently(12, i -> () ->
                transferService.transfer(a.userId(), b.accountNumber(), new BigDecimal("100.00"), "double-click"));

        assertThat(failures(results)).as("duplicates must replay, not fail").isEmpty();
        long distinctTransactions = results.stream().map(r -> r.value().transactionId()).distinct().count();
        assertThat(distinctTransactions).as("all 12 responses describe the one real transaction").isEqualTo(1);
        assertThat(balanceOf(a)).isEqualByComparingTo("400.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("100.00");
        assertThat(transferCount(a)).isEqualTo(1);
    }

    @Test
    void concurrentWithdrawalsCanNeverOverdrawTheAccount() throws Exception {
        TestUser a = newUser("100.00");

        // 10 simultaneous withdrawals of 30 against 100: at most 3 can ever succeed
        List<Outcome<?>> results = new ArrayList<>(runConcurrently(10, i -> () ->
                accountService.withdraw(a.userId(), new BigDecimal("30.00"))));

        long ok = successes(results);
        assertThat(ok).isBetween(1L, 3L);
        assertThat(balanceOf(a)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(balanceOf(a)).isEqualByComparingTo(new BigDecimal("100.00").subtract(new BigDecimal("30.00").multiply(BigDecimal.valueOf(ok))));
        // the only acceptable reasons to lose the race
        results.stream().filter(r -> !r.succeeded()).forEach(r ->
                assertThat(r.error()).isInstanceOfAny(ObjectOptimisticLockingFailureException.class, InsufficientFundsException.class));
    }

    @Test
    void concurrentDepositsNeverLoseAnUpdate() throws Exception {
        TestUser a = newUser("0.00");

        // Without @Version, two deposits could read the same balance and one would vanish
        List<Outcome<?>> results = new ArrayList<>(runConcurrently(10, i -> () ->
                accountService.deposit(a.userId(), new BigDecimal("10.00"))));

        long ok = successes(results);
        assertThat(ok).isGreaterThanOrEqualTo(1);
        assertThat(balanceOf(a)).as("balance must equal exactly the deposits that reported success")
                .isEqualByComparingTo(new BigDecimal("10.00").multiply(BigDecimal.valueOf(ok)));
        results.stream().filter(r -> !r.succeeded()).forEach(r ->
                assertThat(r.error()).isInstanceOf(ObjectOptimisticLockingFailureException.class));
    }

    @Test
    void randomTransfersBetweenManyAccountsConserveTheTotal() throws Exception {
        int accounts = 6;
        List<TestUser> users = new ArrayList<>();
        for (int i = 0; i < accounts; i++) {
            users.add(newUser("100.00"));
        }
        Random random = new Random(42); // fixed seed: same schedule of transfers every run
        int transfers = 60;
        int[] from = new int[transfers];
        int[] to = new int[transfers];
        BigDecimal[] amount = new BigDecimal[transfers];
        for (int i = 0; i < transfers; i++) {
            from[i] = random.nextInt(accounts);
            do {
                to[i] = random.nextInt(accounts);
            } while (to[i] == from[i]);
            amount[i] = BigDecimal.valueOf(1 + random.nextInt(60));
        }
        String run = UUID.randomUUID().toString();

        List<Outcome<TransferResponse>> results = runConcurrently(transfers, i -> () ->
                transferService.transfer(users.get(from[i]).userId(), users.get(to[i]).accountNumber(), amount[i], "rnd-" + run + "-" + i));

        // some may be refused for lack of funds, which is fine; nothing else may go wrong
        results.stream().filter(r -> !r.succeeded()).forEach(r ->
                assertThat(r.error()).isInstanceOf(InsufficientFundsException.class));
        BigDecimal total = BigDecimal.ZERO;
        for (TestUser u : users) {
            BigDecimal balance = balanceOf(u);
            assertThat(balance).as("no account may go negative").isGreaterThanOrEqualTo(BigDecimal.ZERO);
            total = total.add(balance);
        }
        assertThat(total).as("money is never created or destroyed").isEqualByComparingTo("600.00");
    }
}
