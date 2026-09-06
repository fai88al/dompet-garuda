-- =====================================================================
-- V9__flagged_transactions_recon_mismatch_unique.sql
-- =====================================================================
--
-- PouchReconciliationJob checks for an existing unresolved RECON_MISMATCH flag
-- before inserting a new one (CLAUDE.md §7 invariant 11), but that check-then-insert
-- is not atomic. Two concurrent reconciliation passes over the same certificate
-- (e.g. the @Scheduled job's immediate first run racing a test-triggered manual
-- run, or two worker replicas both missing ShedLock's coverage for a
-- directly-invoked method) can both pass the check before either inserts,
-- producing two flags for one mismatch.
--
-- This partial unique index makes the insert itself the final guard, the same
-- pattern already used for idempotency_keys (CLAUDE.md §7 rule 4): at most one
-- unresolved RECON_MISMATCH flag per certificate. NULL certificate_id (used by
-- other flag reasons keyed on offline_txn_id instead) is excluded — Postgres
-- treats each NULL as distinct anyway, but the predicate makes the intent explicit.
CREATE UNIQUE INDEX uq_flagged_unresolved_recon_mismatch
    ON flagged_transactions (certificate_id, reason)
    WHERE resolved = false AND certificate_id IS NOT NULL;
