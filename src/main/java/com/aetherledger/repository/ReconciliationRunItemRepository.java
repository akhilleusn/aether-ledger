package com.aetherledger.repository;

import com.aetherledger.domain.entity.ReconciliationRunItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReconciliationRunItemRepository extends JpaRepository<ReconciliationRunItem, UUID> {}
