-- =====================================================================
-- V8__device_id_to_varchar.sql — FR27/R18: deviceId is no longer a UUID
-- =====================================================================
-- CLAUDE.md §1a: deviceId is now sourced from the hardware team's own
-- identifier scheme (likely MAC-derived), not a backend-generated UUID v4.
-- Existing rows hold UUID-formatted strings, which remain valid VARCHAR
-- values as-is — no data transformation needed, only the column type
-- constraint changes.
--
-- Postgres refuses to change a PK's type while a foreign key still
-- references it with the old type ("foreign key constraint ... cannot be
-- implemented: ... incompatible types"), so every FK to devices.device_id
-- is dropped first and re-added at the end, once every column involved is
-- VARCHAR(128):
--   accounts.device_id               (only for POUCH accounts)
--   offline_certificates.device_id
--   sync_inbox.device_id
--   offline_transactions.sender_device_id
--   offline_transactions.receiver_device_id
--   idempotency_keys.device_id
-- =====================================================================

-- 1. Drop every FK referencing devices.device_id (default Postgres names,
--    since V1/V5 declared them inline without an explicit CONSTRAINT name).
ALTER TABLE accounts               DROP CONSTRAINT accounts_device_id_fkey;
ALTER TABLE offline_certificates   DROP CONSTRAINT offline_certificates_device_id_fkey;
ALTER TABLE sync_inbox             DROP CONSTRAINT sync_inbox_device_id_fkey;
ALTER TABLE offline_transactions   DROP CONSTRAINT offline_transactions_sender_device_id_fkey;
ALTER TABLE offline_transactions   DROP CONSTRAINT offline_transactions_receiver_device_id_fkey;
ALTER TABLE idempotency_keys       DROP CONSTRAINT idempotency_keys_device_id_fkey;

-- 2. Change the PK column type, dropping the UUID-generating default.
ALTER TABLE devices
    ALTER COLUMN device_id TYPE VARCHAR(128) USING device_id::text,
    ALTER COLUMN device_id DROP DEFAULT;

-- 3. Change every referencing column to match.
ALTER TABLE accounts
    ALTER COLUMN device_id TYPE VARCHAR(128) USING device_id::text;

ALTER TABLE offline_certificates
    ALTER COLUMN device_id TYPE VARCHAR(128) USING device_id::text;

ALTER TABLE sync_inbox
    ALTER COLUMN device_id TYPE VARCHAR(128) USING device_id::text;

ALTER TABLE offline_transactions
    ALTER COLUMN sender_device_id   TYPE VARCHAR(128) USING sender_device_id::text,
    ALTER COLUMN receiver_device_id TYPE VARCHAR(128) USING receiver_device_id::text;

ALTER TABLE idempotency_keys
    ALTER COLUMN device_id TYPE VARCHAR(128) USING device_id::text;

-- 4. Re-add the foreign keys, now that both sides are VARCHAR(128).
ALTER TABLE accounts
    ADD CONSTRAINT accounts_device_id_fkey FOREIGN KEY (device_id) REFERENCES devices(device_id);
ALTER TABLE offline_certificates
    ADD CONSTRAINT offline_certificates_device_id_fkey FOREIGN KEY (device_id) REFERENCES devices(device_id);
ALTER TABLE sync_inbox
    ADD CONSTRAINT sync_inbox_device_id_fkey FOREIGN KEY (device_id) REFERENCES devices(device_id);
ALTER TABLE offline_transactions
    ADD CONSTRAINT offline_transactions_sender_device_id_fkey FOREIGN KEY (sender_device_id) REFERENCES devices(device_id);
ALTER TABLE offline_transactions
    ADD CONSTRAINT offline_transactions_receiver_device_id_fkey FOREIGN KEY (receiver_device_id) REFERENCES devices(device_id);
ALTER TABLE idempotency_keys
    ADD CONSTRAINT idempotency_keys_device_id_fkey FOREIGN KEY (device_id) REFERENCES devices(device_id);

-- 5. Enforce the character prohibition at the DB layer (CLAUDE.md §1a): '/' would
--    create unintended MQTT topic sub-levels under wallet/{deviceId}/#, and '|'
--    would break parsing of the offline signature message format. The
--    application-level check in AdminService is the primary UX (a clean 400);
--    this CHECK constraint is the backstop.
ALTER TABLE devices
    ADD CONSTRAINT device_id_no_forbidden_chars CHECK (device_id !~ '[/|]');
