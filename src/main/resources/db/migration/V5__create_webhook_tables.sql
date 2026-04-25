-- ---------------------------------------------------------------------------
-- V5: Webhook subscription and delivery tables
--
-- webhook_subscriptions: each row is an external caller's interest in
--   receiving a specific domain event at a given URL.
--
-- webhook_deliveries: immutable delivery attempt record written each time
--   the relay dispatches an outbox event to a matching subscription.
--
-- Design notes:
--   • No foreign keys into other tables — webhooks are a delivery transport
--     concern and must survive aggregate deletion or archival.
--   • Soft-delete via active=false; rows are never physically removed.
--   • event_type values must match the OutboxEventType enum (same CHECK set
--     as outbox_events.event_type in V3).
-- ---------------------------------------------------------------------------

CREATE TABLE webhook_subscriptions (
    id          UUID            NOT NULL,
    target_url  VARCHAR(2048)   NOT NULL,
    event_type  VARCHAR(60)     NOT NULL,
    active      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ     NOT NULL,

    CONSTRAINT pk_webhook_subscriptions
        PRIMARY KEY (id),

    CONSTRAINT chk_webhook_sub_event_type
        CHECK (event_type IN (
            'TRANSACTION_POSTED',
            'TRANSACTION_COMPLETED',
            'TRANSACTION_REVERSED',
            'TRANSACTION_RECONCILED',
            'BATCH_RECONCILIATION_COMPLETED'
        ))
);

-- Fast lookup: "which active subscriptions match this event type?"
CREATE INDEX idx_webhook_sub_active_event
    ON webhook_subscriptions (event_type)
    WHERE active = TRUE;

CREATE TABLE webhook_deliveries (
    id               UUID            NOT NULL,
    subscription_id  UUID            NOT NULL,
    outbox_event_id  UUID            NOT NULL,
    target_url       VARCHAR(2048)   NOT NULL,
    event_type       VARCHAR(60)     NOT NULL,
    payload          TEXT            NOT NULL,
    status           VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    attempt_count    INT             NOT NULL DEFAULT 0,
    last_error       TEXT,
    created_at       TIMESTAMPTZ     NOT NULL,
    delivered_at     TIMESTAMPTZ,

    CONSTRAINT pk_webhook_deliveries
        PRIMARY KEY (id),

    CONSTRAINT chk_webhook_delivery_status
        CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED'))
);

CREATE INDEX idx_webhook_delivery_subscription
    ON webhook_deliveries (subscription_id);

CREATE INDEX idx_webhook_delivery_outbox_event
    ON webhook_deliveries (outbox_event_id);

CREATE INDEX idx_webhook_delivery_pending
    ON webhook_deliveries (status)
    WHERE status = 'PENDING';
