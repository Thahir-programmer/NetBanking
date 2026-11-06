package com.thahirudeen.netbanking.account;

import com.thahirudeen.netbanking.common.AccountFrozenException;
import com.thahirudeen.netbanking.common.InsufficientFundsException;
import com.thahirudeen.netbanking.common.NotFoundException;
import com.thahirudeen.netbanking.transaction.Transaction;
import com.thahirudeen.netbanking.transaction.TransactionRepository;
import com.thahirudeen.netbanking.transaction.TransactionStatus;
import com.thahirudeen.netbanking.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final Long USER_ID = 7L;

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @InjectMocks AccountService service;

    private Account accountWith(String balance, AccountStatus status) {
        Account a = new Account();
        a.setId(1L);
        a.setAccountNumber("100000000001");
        a.setBalance(new BigDecimal(balance));
        a.setStatus(status);
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void depositAddsToBalanceAndRecordsADepositTransaction() {
        Account account = accountWith("100.00", AccountStatus.ACTIVE);

        AccountResponse response = service.deposit(USER_ID, new BigDecimal("50.25"));

        assertThat(account.getBalance()).isEqualByComparingTo("150.25");
        assertThat(response.balance()).isEqualByComparingTo("150.25");

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(saved.getValue().getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("50.25");
        assertThat(saved.getValue().getToAccount()).isSameAs(account);
        assertThat(saved.getValue().getFromAccount()).isNull();
    }

    @Test
    void withdrawSubtractsFromBalanceAndRecordsAWithdrawal() {
        Account account = accountWith("100.00", AccountStatus.ACTIVE);

        service.withdraw(USER_ID, new BigDecimal("30.50"));

        assertThat(account.getBalance()).isEqualByComparingTo("69.50");
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(saved.getValue().getFromAccount()).isSameAs(account);
        assertThat(saved.getValue().getToAccount()).isNull();
    }

    @Test
    void withdrawingTheExactBalanceIsAllowed() {
        Account account = accountWith("100.00", AccountStatus.ACTIVE);

        service.withdraw(USER_ID, new BigDecimal("100.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void withdrawingMoreThanTheBalanceFailsAndChangesNothing() {
        Account account = accountWith("100.00", AccountStatus.ACTIVE);

        assertThatThrownBy(() -> service.withdraw(USER_ID, new BigDecimal("100.01")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void frozenAccountCannotDeposit() {
        Account account = accountWith("100.00", AccountStatus.FROZEN);

        assertThatThrownBy(() -> service.deposit(USER_ID, new BigDecimal("10.00")))
                .isInstanceOf(AccountFrozenException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void frozenAccountCannotWithdraw() {
        Account account = accountWith("100.00", AccountStatus.FROZEN);

        assertThatThrownBy(() -> service.withdraw(USER_ID, new BigDecimal("10.00")))
                .isInstanceOf(AccountFrozenException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void frozenAccountCanStillBeRead() {
        accountWith("100.00", AccountStatus.FROZEN);

        AccountResponse response = service.getMyAccount(USER_ID);

        assertThat(response.status()).isEqualTo(AccountStatus.FROZEN);
        assertThat(response.balance()).isEqualByComparingTo("100.00");
    }

    @Test
    void unknownUserHasNoAccount() {
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyAccount(USER_ID)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.deposit(USER_ID, BigDecimal.ONE)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void balanceStaysExactAfterManySmallAmounts() {
        // 0.1 added ten times must be exactly 1.00 (a double would give 0.9999999999999999)
        Account account = accountWith("0.00", AccountStatus.ACTIVE);

        for (int i = 0; i < 10; i++) {
            service.deposit(USER_ID, new BigDecimal("0.10"));
        }

        assertThat(account.getBalance()).isEqualByComparingTo("1.00");
    }
}
