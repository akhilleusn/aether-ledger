package com.aetherledger.api.dto;

import java.time.Instant;
import java.util.UUID;

public record IntegritySnapshotResponse(
    UUID    id,
    Instant generatedAt,
    boolean healthy,
    long    totalTransactions,
    long    successCount,
    long    pendingCount,
    long    failedCount
) {}
