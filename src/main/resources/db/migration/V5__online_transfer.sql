-- =====================================================================
-- V5__online_transfer.sql — FR18/FR19: online transfer + idempotency
-- =====================================================================

-- Widen the ledger_transactions.type check constraint to allow the new
-- synchronous ONLINE_TRANSFER posting type (CLAUDE.md §3, §14.1).
ALTER TABLE ledger_transactions
    DROP CONSTRAINT ledger_transactions_type_check,
    ADD CONSTRAINT ledger_transactions_type_check
        CHECK (type IN ('TOPUP', 'POUCH_LOAD', 'OFFLINE_TRANSFER', 'POUCH_REFUND', 'ONLINE_TRANSFER'));

-- Dedicated idempotency store for online, server-mediated money movements
-- (CLAUDE.md §7 rule 4, §14.4). Shared by Transfer Online (this migration)
-- and Bayar QR Online (upcoming PR) — `endpoint` distinguishes callers.
--
-- The UNIQUE constraint on idempotency_key (not a composite key) is the
-- final guard against duplicate submission: the device generates a UUID v4,
-- so a global uniqueness requirement is safe and simpler than per-device
-- scoping while still satisfying "duplicate key for this device" lookups.
CREATE TABLE idempotency_keys (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id        UUID NOT NULL REFERENCES devices(device_id),
    idempotency_key  UUID NOT NULL,
    endpoint         VARCHAR(64) NOT NULL,
    transaction_id   BIGINT REFERENCES ledger_transactions(transaction_id),
    response_status  INT NOT NULL,
    response_body    JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key)
);
