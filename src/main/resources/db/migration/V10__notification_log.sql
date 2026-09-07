-- =====================================================================
-- V10__notification_log.sql — Phase 3 Feature A: Notification Reconciliation
-- (CLAUDE.md §17, PRD FR28-adjacent milestone)
-- =====================================================================
-- Tracks payment-received MQTT notification delivery for OFFLINE_TRANSFER
-- settlements only. This is delivery-metadata, NOT a source of balance truth —
-- GET /device/balance must never reference this table (CLAUDE.md §7 invariant 8,
-- §17 point 6).
-- =====================================================================

CREATE TABLE notification_log (
    id                     BIGSERIAL PRIMARY KEY,
    offline_transaction_id UUID NOT NULL REFERENCES offline_transactions(offline_txn_id),
    device_id              VARCHAR(128) NOT NULL REFERENCES devices(device_id),
    status                 VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING', 'DELIVERED', 'EXPIRED')),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at           TIMESTAMPTZ,
    expires_at             TIMESTAMPTZ NOT NULL
);

-- Reconciliation-on-reconnect query: PENDING rows for one device.
CREATE INDEX idx_notification_log_device_status ON notification_log(device_id, status);

-- Hourly expiry sweep query: stale PENDING rows past their expiry.
CREATE INDEX idx_notification_log_expiry ON notification_log(status, expires_at) WHERE status = 'PENDING';
