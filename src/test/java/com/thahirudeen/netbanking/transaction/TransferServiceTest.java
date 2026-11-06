package com.thahirudeen.netbanking.transaction;

import com.thahirudeen.netbanking.account.Account;
import com.thahirudeen.netbanking.account.AccountRepository;
import com.thahirudeen.netbanking.account.AccountStatus;
import com.thahirudeen.netbanking.common.AccountFrozenException;
import com.thahirudeen.netbanking.common.ConflictException;
import com.thahirudeen.netbanking.common.InsufficientFundsException;
import com.thahirudeen.netbanking.common.InvalidTransferException;
import com.thahirudeen.netbanking.common.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final Long USER_ID = 10L;

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @InjectMocks TransferService service;

    private Account account(long id, String balance) {
        Account a = new Account();
        a.setId(id);
        a.setAccountNumber("10000000000" + id);
        a.setBalance(new BigDecimal(balance));
        a.setStatus(AccountStatus.ACTIVE);
        return a;
    }

    /** Wires the repositories so the caller owns account `mine` and sends to `target`. */
    private void wire(Account mine, Account target) {
        when(accountRepository.findIdByUserId(USER_ID)).thenReturn(Optional.of(mine.getId()));
        when(accountRepository.findIdByAccountNumber(target.getAccountNumber())).thenReturn(Optional.of(target.getId()));
        when(accountRepository.findByIdForUpdate(mine.getId())).thenReturn(Optional.of(mine));
        when(accountRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
    }

    @Test
    void movesMoneyAndRecordsOneTransferWithTheScopedReferenceId() {
        Account a = account(1, "100.00");
        Account b = account(2, "100.00");
        wire(a, b);

        TransferResponse response = service.transfer(USER_ID, b.getAccountNumber(), new BigDecimal("30.00"), "key-1");

        assertThat(a.getBalance()).isEqualByComparingTo("70.00");
        assertThat(b.getBalance()).isEqualByComparingTo("130.00");
        assertThat(response.amount()).isEqualByComparingTo("30.00");

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(saved.getValue().getFromAccount()).isSameAs(a);
        assertThat(saved.getValue().getToAccount()).isSameAs(b);
        assertThat(saved.getValue().getReferenceId()).isEqualTo("1:key-1"); // senderAccountId:key
    }

    @Test
    void locksTheLowerAccountIdFirstEvenWhenSendingFromTheHigherOne() {
        Account high = account(5, "100.00");
        Account low = account(2, "100.00");
        wire(high, low); // caller owns id 5, sends to id 2

        service.transfer(USER_ID, low.getAccountNumber(), new BigDecimal("10.00"), "key-1");

        InOrder order = inOrder(accountRepository);
        order.verify(accountRepository).findByIdForUpdate(2L);
        order.verify(accountRepository).findByIdForUpdate(5L);
    }

    @Test
    void locksTheLowerAccountIdFirstWhenSendingFromTheLowerOne() {
        Account low = account(2, "100.00");
        Account high = account(5, "100.00");
        wire(low, high);

        service.transfer(USER_ID, high.getAccountNumber(), new BigDecimal("10.00"), "key-1");

        InOrder order = inOrder(accountRepository);
        order.verify(accountRepository).findByIdForUpdate(2L);
        order.verify(accountRepository).findByIdForUpdate(5L);
    }

    @Test
    void retryWithTheSameKeyReturnsTheOriginalAndMovesNoMoney() {
        Account a = account(1, "70.00");
        Account b = account(2, "130.00");
        wire(a, b);
        Transaction original = new Transaction();
        original.setId(99L);
        original.setType(TransactionType.TRANSFER);
        original.setAmount(new BigDecimal("30.00"));
        original.setFromAccount(a);
        original.setToAccount(b);
        original.setStatus(TransactionStatus.SUCCESS);
        when(transactionRepository.findByReferenceId("1:key-1")).thenReturn(Optional.of(original));

        TransferResponse response = service.transfer(USER_ID, b.getAccountNumber(), new BigDecimal("30.00"), "key-1");

        assertThat(response.transactionId()).isEqualTo(99L);
        assertThat(a.getBalance()).isEqualByComparingTo("70.00");
        assertThat(b.getBalance()).isEqualByComparingTo("130.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void retryStillReplaysEvenIfTheSenderCanNoLongerAffordIt() {
        // The first attempt drained the account. The retry must still say "already done", not "insufficient funds".
        Account a = account(1, "0.00");
        Account b = account(2, "100.00");
        wire(a, b);
        Transaction original = new Transaction();
        original.setId(99L);
        original.setAmount(new BigDecimal("100.00"));
        original.setFromAccount(a);
        original.setToAccount(b);
        original.setStatus(TransactionStatus.SUCCESS);
        when(transactionRepository.findByReferenceId("1:key-1")).thenReturn(Optional.of(original));

        TransferResponse response = service.transfer(USER_ID, b.getAccountNumber(), new BigDecimal("100.00"), "key-1");

        assertThat(response.transactionId()).isEqualTo(99L);
    }

    @Test
    void reusingAKeyForADifferentAmountIsRejected() {
        Account a = account(1, "70.00");
        Account b = account(2, "130.00");
        wire(a, b);
        Transaction original = new Transaction();
        original.setId(99L);
        original.setAmount(new BigDecimal("30.00"));
        original.setFromAccount(a);
        original.setToAccount(b);
        when(transactionRepository.findByReferenceId("1:key-1")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.transfer(USER_ID, b.getAccountNumber(), new BigDecimal("99.00"), "key-1"))
                .isInstanceOf(ConflictException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void insufficientFundsFailsAndChangesNothing() {
        Account a = account(1, "10.00");
        Account b = account(2, "100.00");
        wire(a, b);

        assertThatThrownBy(() -> service.transfer(USER_ID, b.getAccountNumber(), new BigDecimal("30.00"), "key-1"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(a.getBalance()).isEqualByComparingTo("10.00");
        assertThat(b.getBalance()).isEqualByComparingTo("100.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transferringTheWholeBalanceIsAllowed() {
        Account a = account(1, "30.00");
        Account b = account(2, "0.00");
        wire(a, b);

        service.transfer(USER_ID, b.getAccountNumber(), new BigDecimal("30.00"), "key-1");

        assertThat(a.getBalance()).isEqualByComparingTo("0.00");
        assertThat(b.getBalance()).isEqualByComparingTo("30.00");
    }

    @Test
    void frozenSenderIsRejected() {
        Account a = account(1, "100.00");
        a.setStatus(AccountStatus.FROZEN);
        Account b = account(2, "100.00");
        wire(a, b);

        assertThatThrownBy(() -> service.transfer(USER_ID, b.getAccountNumber(), new BigDecimal("10.00"), "key-1"))
                .isInstanceOf(AccountFrozenException.class);
        assertThat(a.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void frozenReceiverIsRejected() {
        Account a = account(1, "100.00");
        Account b = account(2, "100.00");
        b.setStatus(AccountStatus.FROZEN);
        wire(a, b);

        assertThatThrownBy(() -> service.transfer(USER_ID, b.getAccountNumber(), new BigDecimal("10.00"), "key-1"))
                .isInstanceOf(AccountFrozenException.class);
        assertThat(a.getBalance()).isEqualByComparingTo("100.00");
        assertThat(b.getBalance()).isEqualByComparingTo("100.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transferToYourOwnAccountIsRejectedBeforeAnyLocking() {
        Account a = account(1, "100.00");
        when(accountRepository.findIdByUserId(USER_ID)).thenReturn(Optional.of(1L));
        when(accountRepository.findIdByAccountNumber(a.getAccountNumber())).thenReturn(Optional.of(1L));

        assertThatThrownBy(() -> service.transfer(USER_ID, a.getAccountNumber(), BigDecimal.TEN, "key-1"))
                .isInstanceOf(InvalidTransferException.class);
        verify(accountRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void unknownDestinationIsNotFound() {
        when(accountRepository.findIdByUserId(USER_ID)).thenReturn(Optional.of(1L));
        when(accountRepository.findIdByAccountNumber("999999999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(USER_ID, "999999999999", BigDecimal.TEN, "key-1"))
                .isInstanceOf(NotFoundException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingOrBlankIdempotencyKeyIsRejectedBeforeTouchingTheDatabase(String key) {
        assertThatThrownBy(() -> service.transfer(USER_ID, "100000000002", BigDecimal.TEN, key))
                .isInstanceOf(InvalidTransferException.class);
        verifyNoInteractions(accountRepository, transactionRepository);
    }

    @Test
    void overlongIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> service.transfer(USER_ID, "100000000002", BigDecimal.TEN, "x".repeat(101)))
                .isInstanceOf(InvalidTransferException.class);
        verifyNoInteractions(accountRepository, transactionRepository);
    }
}
