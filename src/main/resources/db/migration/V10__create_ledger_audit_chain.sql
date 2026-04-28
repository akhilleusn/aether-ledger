-- ============================================================
-- AetherLedger — V10  Ledger audit hash chain
-- ============================================================
--
-- Tamper-evident append-only chain.  Each row stores a SHA-256
-- hash that covers the previous row's hash plus the current
-- event's fields.  Modifying any persisted row will break the
-- chain, which is detected by GET /api/v1/ops/audit-chain/verify.
--
-- Design choices:
--   • sequence_number is application-managed (not a DB sequence)
--     so that hash computation is fully deterministic even after
--     a transaction rollback (no sequence gaps matter here because
--     a rolled-back append never produces a committed chain entry).
--   • entity_id references the domain entity UUID without a FK
--     constraint so the chain remains intact even if the source
--     entity is deleted via operational tooling.
--   • All columns are NOT NULL except previous_hash (NULL only for
--     the genesis entry at sequence_number = 1).
-- ============================================================

CREATE TABLE ledger_audit_chain (
    id               UUID         NOT NULL,
    sequence_number  BIGINT       NOT NULL,
    event_type       VARCHAR(100) NOT NULL,
    entity_type      VARCHAR(100) NOT NULL,
    entity_id        UUID         NOT NULL,
    payload_hash     VARCHAR(64)  NOT NULL,
    previous_hash    VARCHAR(64),
    current_hash     VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_ledger_audit_chain          PRIMARY KEY (id),
    CONSTRAINT uq_ledger_audit_chain_seq_num  UNIQUE (sequence_number)
);

CREATE INDEX idx_ledger_audit_chain_entity_id  ON ledger_audit_chain (entity_id);
CREATE INDEX idx_ledger_audit_chain_event_type ON ledger_audit_chain (event_type);
CREATE INDEX idx_ledger_audit_chain_created_at ON ledger_audit_chain (created_at DESC);
