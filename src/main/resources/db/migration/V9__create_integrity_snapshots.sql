-- ============================================================
-- AetherLedger — V9  Integrity snapshots
-- ============================================================
--
-- Append-only point-in-time record of ledger health.
-- Created by POST /api/v1/ops/integrity/snapshots; never updated.
-- ============================================================

CREATE TABLE integrity_snapshots (
    id                  UUID        NOT NULL,
    generated_at        TIMESTAMPTZ NOT NULL,
    healthy             BOOLEAN     NOT NULL,
    total_transactions  BIGINT      NOT NULL,
    success_count       BIGINT      NOT NULL,
    pending_count       BIGINT      NOT NULL,
    failed_count        BIGINT      NOT NULL,

    CONSTRAINT pk_integrity_snapshots PRIMARY KEY (id)
);

CREATE INDEX idx_integrity_snapshots_generated_at
    ON integrity_snapshots (generated_at DESC);
