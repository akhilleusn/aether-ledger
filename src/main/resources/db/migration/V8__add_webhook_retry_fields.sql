-- ---------------------------------------------------------------------------
-- V8: Webhook retry and dead-letter support
--
-- 1. Extend the delivery status CHECK constraint to include DEAD.
-- 2. Add next_attempt_at — when the next scheduled retry should fire;
--    NULL for SUCCESS and DEAD deliveries.
-- 3. Add max_attempts — how many attempts are allowed before the delivery
--    is moved to the dead-letter state.
-- 4. Add targeted indexes for the retry scanner.
-- ---------------------------------------------------------------------------

-- 1. Extend status CHECK to include DEAD
ALTER TABLE webhook_deliveries DROP CONSTRAINT chk_webhook_delivery_status;
ALTER TABLE webhook_deliveries
    ADD CONSTRAINT chk_webhook_delivery_status
    CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'DEAD'));

-- 2 & 3. New retry columns
ALTER TABLE webhook_deliveries
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN max_attempts    INTEGER NOT NULL DEFAULT 5;

-- 4a. General status filter (failed deliveries endpoint)
CREATE INDEX idx_webhook_delivery_status
    ON webhook_deliveries (status);

-- 4b. Retry scanner — only FAILED rows that are due
CREATE INDEX idx_webhook_delivery_retry_due
    ON webhook_deliveries (next_attempt_at)
    WHERE status = 'FAILED' AND next_attempt_at IS NOT NULL;
