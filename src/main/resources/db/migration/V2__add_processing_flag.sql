-- ---------------------------------------------------------------------------
-- V2: Add processing flag to ledger_transactions
--
-- The scheduled reconciliation job sets this flag to true when it claims a
-- transaction for processing.  Combined with the @Version optimistic-lock
-- column, this prevents two concurrent scheduler instances from reconciling
-- the same transaction simultaneously.
--
-- If the scheduler crashes after claiming but before committing the result,
-- the flag stays true and the transaction will not be retried automatically.
-- A manual reset or a separate maintenance job would be needed in production.
-- ---------------------------------------------------------------------------
ALTER TABLE ledger_transactions
    ADD COLUMN processing BOOLEAN NOT NULL DEFAULT FALSE;
