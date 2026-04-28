package com.aetherledger.repository;

import com.aetherledger.domain.entity.IntegritySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IntegritySnapshotRepository extends JpaRepository<IntegritySnapshot, UUID> {

    List<IntegritySnapshot> findAllByOrderByGeneratedAtDesc();
}
