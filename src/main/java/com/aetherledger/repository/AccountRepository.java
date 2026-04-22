package com.aetherledger.repository;

import com.aetherledger.domain.entity.Account;
import com.aetherledger.domain.enums.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByName(String name);

    boolean existsByName(String name);

    List<Account> findByType(AccountType type);

    /**
     * Acquires a pessimistic write lock on the account row before any
     * operation that reads the current balance and then writes an entry —
     * prevents phantom reads under concurrent load.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithLock(@Param("id") UUID id);
}
