-- =====================================================================
-- V11__admin_access_log.sql — Phase 3 Feature B: Transaction History
-- (CLAUDE.md §18, PRD v1.1 §3.4)
-- =====================================================================
-- Audits every call to GET /admin/users/{userId}/transactions: who looked,
-- at whose data, when, with what filters. The access itself is what's
-- audited — written even when the query returns zero transactions.
--
-- admin_user_id is the JWT subject (see JwtService) but is intentionally
-- NOT a foreign key to admin_users: this is an append-only audit trail and
-- must keep recording accesses even if the admin_users row is later
-- deactivated/removed, and must never fail an audit write over an admin
-- identity mismatch. user_id IS a real FK: every access is against a real
-- user record, and losing that join would defeat the audit's purpose.
-- =====================================================================

CREATE TABLE admin_access_log (
    id            BIGSERIAL PRIMARY KEY,
    admin_user_id UUID         NOT NULL,
    user_id       UUID         NOT NULL REFERENCES users(user_id),
    query_params  TEXT,
    accessed_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_access_log_user ON admin_access_log(user_id, accessed_at);
