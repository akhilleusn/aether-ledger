-- ---------------------------------------------------------------------------
-- V3: Transactional outbox table
--
-- outbox_events stores domain events that must be relayed to an external
-- broker (Kafka, SNS, etc.) exactly once.  Each row is written in the SAME
-- database transaction as the business operation that produced it, so an
-- event is never lost due to an application crash between the write and the
-- publish step.
--
-- A relay process (future work) polls for rows WHERE published = false,
-- publishes them, and flips published = true.  The partial index below makes
-- this poll extremely cheap: it only covers unpublished rows.
--
-- Design notes:
--   • payload is TEXT (not JSONB) so the schema stays portable across
--     database engines and does not need to know the internal structure.
--   • aggregate_id / aggregate_type let a relay route events to the correct
--     topic without parsing the payload.
--   • No foreign keys into other tables — the outbox is a transport concern,
--     not a relational one.  Rows survive even if the referenced aggregate is
--     later archived or deleted.
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id             UUID        NOT NULL,
    event_type     VARCHAR(60) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    aggregate_type VARCHAR(60) NOT NULL,
    payload        TEXT        NOT NULL,
    published      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_outbox_events
        PRIMARY KEY (id),

    CONSTRAINT chk_outbox_event_type
        CHECK (event_type IN (
            'TRANSACTION_POSTED',
            'TRANSACTION_COMPLETED',
            'TRANSACTION_REVERSED',
            'TRANSACTION_RECONCILED',
            'BATCH_RECONCILIATION_COMPLETED'
        ))
);

-- Partial index: only indexes unpublished rows.
-- The relay's "find next batch to publish" query hits this index directly.
CREATE INDEX idx_outbox_unpublished
    ON outbox_events (created_at)
    WHERE published = false;
