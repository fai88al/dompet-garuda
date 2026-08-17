-- =====================================================================
-- V6__payment_requests.sql — FR20/FR21/FR22: Bayar QR Online
-- =====================================================================

ALTER TABLE ledger_transactions
    DROP CONSTRAINT ledger_transactions_type_check,
    ADD CONSTRAINT ledger_transactions_type_check
        CHECK (type IN ('TOPUP', 'POUCH_LOAD', 'OFFLINE_TRANSFER', 'POUCH_REFUND',
                         'ONLINE_TRANSFER', 'QR_PAYMENT_ONLINE'));

CREATE TABLE payment_requests (
    request_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receiver_user_id       UUID NOT NULL REFERENCES users(user_id),
    amount                 BIGINT NOT NULL CHECK (amount > 0),
    nonce                  VARCHAR(64) NOT NULL UNIQUE,
    status                 VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                              CHECK (status IN ('PENDING', 'PAID', 'EXPIRED')),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at             TIMESTAMPTZ NOT NULL,
    paid_at                TIMESTAMPTZ,
    paid_by_user_id        UUID REFERENCES users(user_id),
    ledger_transaction_id  BIGINT REFERENCES ledger_transactions(transaction_id)
);
CREATE INDEX idx_payment_requests_status ON payment_requests(status);
