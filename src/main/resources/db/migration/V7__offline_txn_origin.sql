-- Bayar QR Offline (FR23) — purely informational origin tracking.
-- Never read by settlement's verification, signature, or pouch-limit logic (§14.3).
ALTER TABLE offline_transactions
    ADD COLUMN origin VARCHAR(10) NOT NULL DEFAULT 'BLE'
        CHECK (origin IN ('BLE', 'QR'));
