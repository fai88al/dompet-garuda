-- =====================================================================
-- V1__init.sql  —  Dompet Digital initial schema (Flyway baseline)
-- Target: PostgreSQL 16
-- =====================================================================
-- Conventions & invariants (see CLAUDE.md):
--   * Money is BIGINT in WHOLE RUPIAH (IDR). Never float/double/numeric for money.
--   * Balances are DERIVED from ledger_entries. There is no mutable balance column.
--   * Every money movement is a balanced set of ledger_entries (debits = credits)
--     grouped by one ledger_transactions row, written in a single DB transaction.
--   * ledger_entries are append-only: never UPDATEd or DELETEd.
--   * Schema changes ONLY via new Flyway migrations. Never edit an applied file.
--   * gen_random_uuid() is built into PostgreSQL 13+ (no extension needed).
-- =====================================================================

-- ---------------------------------------------------------------------
-- USERS — the account holder
-- ---------------------------------------------------------------------
CREATE TABLE users (
    user_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name   VARCHAR(120) NOT NULL,
    phone       VARCHAR(20)  NOT NULL UNIQUE,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- DEVICES — a physical Dompet unit, bound to one user
-- ---------------------------------------------------------------------
CREATE TABLE devices (
    device_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(user_id),
    public_key    TEXT NOT NULL,                  -- Ed25519 public key (base64)
    device_label  VARCHAR(60),
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'LOCKED')),
    last_counter  BIGINT NOT NULL DEFAULT 0,      -- highest settled sender counter (replay guard)
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_devices_user ON devices(user_id);
-- DECISION R3: max 3 devices per user — enforced in application code at
-- registration (kept out of the schema to keep the rule visible & testable).

-- ---------------------------------------------------------------------
-- ACCOUNTS — ledger accounts.
--   SYSTEM : single house/funding account (source of top-ups)
--   ONLINE : exactly one per user
--   POUCH  : one per device (the server-side mirror of the offline pouch)
-- ---------------------------------------------------------------------
CREATE TABLE accounts (
    account_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID REFERENCES users(user_id),     -- NULL for SYSTEM
    device_id   UUID REFERENCES devices(device_id), -- only for POUCH
    type        VARCHAR(16) NOT NULL
                  CHECK (type IN ('SYSTEM', 'ONLINE', 'POUCH')),
    status      VARCHAR(16) NOT NULL DEFAULT 'OPEN'
                  CHECK (status IN ('OPEN', 'CLOSED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- exactly one ONLINE account per user
CREATE UNIQUE INDEX uq_online_per_user ON accounts(user_id) WHERE type = 'ONLINE';
-- one POUCH account per device
CREATE UNIQUE INDEX uq_pouch_per_device ON accounts(device_id) WHERE type = 'POUCH';
CREATE INDEX idx_accounts_user ON accounts(user_id);

-- Seed the single SYSTEM funding account (fixed id for easy reference).
INSERT INTO accounts(account_id, type, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'SYSTEM', 'OPEN');

-- ---------------------------------------------------------------------
-- LEDGER_TRANSACTIONS — a journal entry that groups balanced postings
-- ---------------------------------------------------------------------
CREATE TABLE ledger_transactions (
    transaction_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type           VARCHAR(32) NOT NULL
                     CHECK (type IN ('TOPUP', 'POUCH_LOAD', 'OFFLINE_TRANSFER', 'POUCH_REFUND')),
    reference_type VARCHAR(32),    -- e.g. 'OFFLINE_TXN', 'CERTIFICATE'
    reference_id   VARCHAR(64),    -- id of the related domain entity
    description    TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- LEDGER_ENTRIES — immutable double-entry postings (append-only)
--   INVARIANT (enforced in app + asserted in tests):
--   per transaction_id, SUM(amount) WHERE direction='CREDIT'
--                     = SUM(amount) WHERE direction='DEBIT'
--   An account balance = SUM(CREDIT) - SUM(DEBIT) over its entries.
-- ---------------------------------------------------------------------
CREATE TABLE ledger_entries (
    entry_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES ledger_transactions(transaction_id),
    account_id     UUID   NOT NULL REFERENCES accounts(account_id),
    direction      VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount         BIGINT NOT NULL CHECK (amount > 0),   -- whole Rupiah
    currency       CHAR(3) NOT NULL DEFAULT 'IDR',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_account ON ledger_entries(account_id);
CREATE INDEX idx_entries_txn     ON ledger_entries(transaction_id);

-- ---------------------------------------------------------------------
-- OFFLINE_CERTIFICATES — server-signed authorization to hold an offline pouch
--   DECISION R3: expires_at = issued_at + 24h (set by application).
--   DECISION Q4: at most one ACTIVE certificate per device at a time.
--   DECISION Q3: at sync, unspent amount is refunded and status -> SETTLED.
-- ---------------------------------------------------------------------
CREATE TABLE offline_certificates (
    certificate_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id         UUID NOT NULL REFERENCES devices(device_id),
    pouch_account_id  UUID NOT NULL REFERENCES accounts(account_id),
    issued_amount     BIGINT NOT NULL CHECK (issued_amount > 0), -- loaded == max spendable offline
    server_signature  TEXT NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'EXPIRED', 'SETTLED', 'REVOKED')),
    issued_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL,
    settled_at        TIMESTAMPTZ
);
CREATE INDEX idx_cert_device ON offline_certificates(device_id);
-- Q4: only one ACTIVE certificate per device
CREATE UNIQUE INDEX uq_active_cert_per_device
    ON offline_certificates(device_id) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------
-- SYNC_INBOX — raw uploaded batches; doubles as the worker's job queue.
--   API writes the raw batch here and returns 202 (no ledger writes).
--   Worker polls:
--     SELECT ... FROM sync_inbox WHERE status='PENDING'
--     ORDER BY received_at FOR UPDATE SKIP LOCKED LIMIT :n;
--   DECISION Q2: synced_after_expiry = true when a device syncs after its
--   certificate expired; such batches are settled but flagged for review.
-- ---------------------------------------------------------------------
CREATE TABLE sync_inbox (
    batch_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id           UUID NOT NULL REFERENCES devices(device_id),
    raw_payload         JSONB NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                          CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
    error_reason        TEXT,
    synced_after_expiry BOOLEAN NOT NULL DEFAULT false,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at        TIMESTAMPTZ
);
CREATE INDEX idx_inbox_status ON sync_inbox(status, received_at);

-- ---------------------------------------------------------------------
-- OFFLINE_TRANSACTIONS — settled record of a BLE transfer.
--   DECISION Q1: at settlement this DEBITs the sender's POUCH account and
--   CREDITs the RECEIVER's ONLINE account (received funds are not a new pouch).
--   DECISION R2: receiver cannot re-spend offline until synced — guaranteed
--   because received value lands in the online account, not a pouch.
--   The UNIQUE(sender_device_id, counter) index is the replay protection.
-- ---------------------------------------------------------------------
CREATE TABLE offline_transactions (
    offline_txn_id     UUID PRIMARY KEY,            -- device-generated
    sender_device_id   UUID NOT NULL REFERENCES devices(device_id),
    receiver_device_id UUID NOT NULL REFERENCES devices(device_id),
    certificate_id     UUID NOT NULL REFERENCES offline_certificates(certificate_id),
    batch_id           UUID REFERENCES sync_inbox(batch_id),
    amount             BIGINT NOT NULL CHECK (amount > 0),
    counter            BIGINT NOT NULL,             -- sender monotonic counter
    device_timestamp   TIMESTAMPTZ,                 -- untrusted; informational only (R6)
    sender_signature   TEXT NOT NULL,
    receiver_signature TEXT NOT NULL,
    settlement_status  VARCHAR(12) NOT NULL DEFAULT 'SETTLED'
                         CHECK (settlement_status IN ('SETTLED', 'FLAGGED', 'REJECTED')),
    settled_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- database-level replay protection (the key integrity constraint)
CREATE UNIQUE INDEX uq_sender_counter ON offline_transactions(sender_device_id, counter);
CREATE INDEX idx_otx_cert     ON offline_transactions(certificate_id);
CREATE INDEX idx_otx_receiver ON offline_transactions(receiver_device_id);
CREATE INDEX idx_otx_batch    ON offline_transactions(batch_id);

-- ---------------------------------------------------------------------
-- FLAGGED_TRANSACTIONS — anomalies caught during settlement/reconciliation.
--   Nothing is ever silently dropped; suspicious work lands here with a reason.
-- ---------------------------------------------------------------------
CREATE TABLE flagged_transactions (
    flag_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    offline_txn_id UUID REFERENCES offline_transactions(offline_txn_id),
    batch_id       UUID REFERENCES sync_inbox(batch_id),
    certificate_id UUID REFERENCES offline_certificates(certificate_id),
    reason         VARCHAR(32) NOT NULL
                     CHECK (reason IN ('OVER_LIMIT', 'BAD_SIGNATURE', 'COUNTER_REPLAY',
                                       'EXPIRED_CERT_LATE_SYNC', 'RECON_MISMATCH', 'MALFORMED')),
    detail         TEXT,
    resolved       BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMPTZ
);
CREATE INDEX idx_flagged_unresolved ON flagged_transactions(resolved) WHERE resolved = false;

-- ---------------------------------------------------------------------
-- SHEDLOCK — single-execution guard for the worker's @Scheduled jobs
-- ---------------------------------------------------------------------
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

-- =====================================================================
-- Posting reference (how each transaction type balances) — for implementers:
--   TOPUP            : DEBIT system        , CREDIT user.online
--   POUCH_LOAD       : DEBIT user.online   , CREDIT device.pouch
--   OFFLINE_TRANSFER : DEBIT sender.pouch  , CREDIT receiver.online   (Q1/R2)
--   POUCH_REFUND     : DEBIT device.pouch  , CREDIT user.online       (Q3, at sync)
-- =====================================================================
