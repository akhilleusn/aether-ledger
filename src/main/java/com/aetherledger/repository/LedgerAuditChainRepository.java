package com.aetherledger.repository;

import com.aetherledger.domain.entity.LedgerAuditChainEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerAuditChainRepository extends JpaRepository<LedgerAuditChainEntry, UUID> {

    /** Returns the most recently appended entry, used to determine the next sequence number. */
    Optional<LedgerAuditChainEntry> findTopByOrderBySequenceNumberDesc();

    /** Returns all entries in ascending sequence order — used for full chain verification. */
    List<LedgerAuditChainEntry> findAllByOrderBySequenceNumberAsc();

    /** Returns entries newest-first for paginated listing. */
    Page<LedgerAuditChainEntry> findAllByOrderBySequenceNumberDesc(Pageable pageable);

    Optional<LedgerAuditChainEntry> findBySequenceNumber(long sequenceNumber);

    List<LedgerAuditChainEntry> findAllByEntityIdOrderBySequenceNumberDesc(UUID entityId);
}
