-- ---------------------------------------------------------------------------
-- V7: Idempotency keys for POST /api/v1/ledger-transactions
--
-- Stores the result of a successful transaction creation keyed by the
-- caller-supplied Idempotency-Key header.  On retry, the relay reads the
-- stored response and returns it verbatim rather than creating a duplicate.
--
-- The request_fingerprint (SHA-256 of the serialised request body) guards
-- against the same key being reused with a different request body.
--
-- Records expire after 24 hours; a future cleanup job may delete rows where
-- expires_at < now().
-- ---------------------------------------------------------------------------

CREATE TABLE idempotency_keys (
    idempotency_key     VARCHAR(255)    NOT NULL,
    request_fingerprint VARCHAR(64)      NOT NULL,
    response_status     INTEGER         NOT NULL,
    response_body       TEXT            NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL,
    expires_at          TIMESTAMPTZ     NOT NULL,

    CONSTRAINT pk_idempotency_keys
        PRIMARY KEY (idempotency_key)
);

-- Supports a scheduled expiry sweep without a full-table scan.
CREATE INDEX idx_idempotency_expires_at
    ON idempotency_keys (expires_at);
