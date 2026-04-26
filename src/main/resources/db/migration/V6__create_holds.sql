-- ---------------------------------------------------------------------------
-- V6: Balance holds — reserved funds
--
-- Step 1: Extend CHECK constraints on outbox_events and
--         webhook_subscriptions to include HOLD_* event types.
-- Step 2: Create the holds table.
-- ---------------------------------------------------------------------------

-- 1a: outbox_events
ALTER TABLE outbox_events DROP CONSTRAINT chk_outbox_event_type;
ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_event_type
    CHECK (event_type IN (
        'TRANSACTION_POSTED',
        'TRANSACTION_COMPLETED',
        'TRANSACTION_REVERSED',
        'TRANSACTION_RECONCILED',
        'BATCH_RECONCILIATION_COMPLETED',
        'HOLD_CREATED',
        'HOLD_CAPTURED',
        'HOLD_RELEASED'
    ));

-- 1b: webhook_subscriptions
ALTER TABLE webhook_subscriptions DROP CONSTRAINT chk_webhook_sub_event_type;
ALTER TABLE webhook_subscriptions
    ADD CONSTRAINT chk_webhook_sub_event_type
    CHECK (event_type IN (
        'TRANSACTION_POSTED',
        'TRANSACTION_COMPLETED',
        'TRANSACTION_REVERSED',
        'TRANSACTION_RECONCILED',
        'BATCH_RECONCILIATION_COMPLETED',
        'HOLD_CREATED',
        'HOLD_CAPTURED',
        'HOLD_RELEASED'
    ));

-- 2: holds
--
-- A hold reserves funds on an account without moving them in the ledger.
-- Funds only move on CAPTURE (a real double-entry transaction is written).
-- RELEASE cancels the reservation with no ledger movement.
--
-- @Version-based optimistic locking guards concurrent capture / release.
CREATE TABLE holds (
    id              UUID            NOT NULL,
    account_id      UUID            NOT NULL,
    amount          NUMERIC(19, 4)  NOT NULL,
    reference_id    VARCHAR(255)    NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL,
    captured_at     TIMESTAMPTZ,
    released_at     TIMESTAMPTZ,

    CONSTRAINT pk_holds
        PRIMARY KEY (id),

    CONSTRAINT uq_holds_reference_id
        UNIQUE (reference_id),

    CONSTRAINT fk_holds_account
        FOREIGN KEY (account_id) REFERENCES accounts(id),

    CONSTRAINT chk_holds_amount
        CHECK (amount > 0),

    CONSTRAINT chk_holds_status
        CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED'))
);

CREATE INDEX idx_holds_account_id
    ON holds (account_id);

-- Fast lookup for held-balance aggregation
CREATE INDEX idx_holds_account_active
    ON holds (account_id)
    WHERE status = 'ACTIVE';
