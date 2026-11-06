package com.thahirudeen.netbanking.transaction;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Optional<Transaction> findByReferenceId(String referenceId);

    // Everything the account sent or received, newest first. Both accounts are fetched
    // in the same query so building the response does not trigger extra queries.
    @Query("""
            select t from Transaction t
            left join fetch t.fromAccount
            left join fetch t.toAccount
            where t.fromAccount.id = :accountId or t.toAccount.id = :accountId
            order by t.createdAt desc, t.id desc
            """)
    List<Transaction> findHistory(@Param("accountId") Long accountId, Pageable pageable);
}
