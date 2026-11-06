package com.thahirudeen.netbanking.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    boolean existsByAccountNumber(String accountNumber);
    Optional<Account> findByUserId(Long userId);
    Optional<Account> findByAccountNumber(String accountNumber);

    // Id-only lookups: they do not load the entity into the session, so the later
    // locking query reads fresh data instead of returning a stale cached copy
    @Query("select a.id from Account a where a.user.id = :userId")
    Optional<Long> findIdByUserId(@Param("userId") Long userId);

    @Query("select a.id from Account a where a.accountNumber = :accountNumber")
    Optional<Long> findIdByAccountNumber(@Param("accountNumber") String accountNumber);

    // SELECT ... FOR UPDATE: other transactions wait here until we commit or roll back
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
