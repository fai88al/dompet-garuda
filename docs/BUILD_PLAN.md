# BUILD_PLAN — Dompet Digital Backend (Claude Code kickoff & sequence)

This is how to hand the project to Claude Code so it builds **incrementally**, one reviewable PR at
a time, instead of generating the whole MVP in one blob. Hand out tasks **one at a time**, reviewing
and merging each before the next. Claude Code reads `CLAUDE.md` automatically; point it at the PRD
for *scope*, not as a build list.

**Progress:** PR1 (scaffold) ✅ merged · PR2 (auth + device registration) ✅ merged · **next: PR3 (ledger core)**

**Before each task:** package root is `com.dompetgaruda.api` (already set in CLAUDE.md and the repo),
your dev datasource points at `localhost:5432` (VPS over SSH tunnel, or a local Postgres), and your
GitHub account has push access to the client repo.

---

## Build order (one PR each — don't batch)

Rationale for the order: the **ledger is the foundation**, so it comes before anything that moves
money; the API service (Phase 4) is finished before the worker (Phase 5).

**API service (Phase 4)**

1. ✅ **PR1 — Scaffold.** Maven/Spring Boot skeleton, `api`/`worker` profiles, Flyway wired, V1 in `db/migration`, smoke test. *(merged)*
2. ✅ **PR2 — Auth + device registration (FR1).** Admin token auth; admin-initiated device registration storing the Ed25519 public key; device-token issue + hash; max 3 devices/user; minimal create-user (+ opens ONLINE account row). *(merged)*
3. **PR3 — Ledger core (the heart).** Double-entry posting service + balance derivation, plain SQL/JdbcTemplate, in one DB transaction. Tests assert credits = debits and correct balances. *Nothing that moves money is built before this. Review this PR the most carefully of all.*
4. **PR4 — Online top-up (FR2).** Admin tops up a user; posts `TOPUP` via the ledger service.
5. **PR4b — Balance enquiry / "Cek Saldo" (FR14).** *New.* A read-only device-authenticated endpoint returning the user's authoritative **online balance** (ledger-derived) + the **pouch committed** amount (active certificate), clearly labelled. No ledger writes. Depends on PR3's balance derivation; placed after PR4 so there's a real balance to show. Test: returned figure equals ledger sum, and the call performs zero writes.
6. **PR5 — Pouch provisioning + certificate (FR3, FR13).** Debit online, issue signed cert (24h expiry), enforce one active cert per device.
7. **PR6 — Sync ingest endpoint (FR5).** Device-auth, write raw batch to `sync_inbox`, return 202. No validation/settlement here. *This completes the API service.*

**Worker service (Phase 5)**

8. **PR7 — Worker bootstrap + inbox poller.** `FOR UPDATE SKIP LOCKED` poll loop, ShedLock on the schedule, status transitions on `sync_inbox`.
9. **PR8 — Settlement (FR4, FR6, FR7, FR8, FR11, FR12 + §9a).** Verify Ed25519 signatures + counters, enforce pouch limit, post `OFFLINE_TRANSFER` (credit receiver online), refund unspent (`POUCH_REFUND`), flag late/over-limit/malformed. The required test cases in CLAUDE.md §10 live here.
10. **PR9 — Reconciliation job (FR9).** Scheduled, ShedLock-wrapped, writes mismatches to flagged.
11. **PR10 — MQTT publisher.** Paho client; publish `sync-result` per CLAUDE.md §6. (Receive-side/status optional.)
12. **PR11 — Admin read endpoints (FR10).** Read-only lists; UI deferred.

**Parallel, anytime after PR6: device simulator.** A small standalone program that generates real
Ed25519-signed batches and calls the sync API — this is what lets you test PR7–PR9 without ESP32
hardware (PRD R1). Worth doing early.

---

## Next prompt to paste — PR3 (ledger core)

```
Read CLAUDE.md again first (especially §3 ledger posting reference, §7 invariants, §10 testing),
and PRD §6. Work on branch `feat/ledger-core`, open a PR against main, don't push to main.

Scope of this task ONLY — the double-entry ledger engine; no user-facing endpoints:
1. A ledger posting service (plain SQL / JdbcTemplate, NOT JPA for the writes) that, in ONE DB
   transaction, inserts a ledger_transactions row plus its balanced ledger_entries
   (sum of CREDIT amounts == sum of DEBIT amounts). Reject any unbalanced posting.
2. A balance-derivation query: an account's balance = SUM(CREDIT) - SUM(DEBIT) over its entries.
3. Helpers to resolve the SYSTEM account and a user's ONLINE / a device's POUCH account.
4. Money is `long` / BIGINT, whole Rupiah. No floats anywhere.
5. Tests (Testcontainers, real Postgres):
   - a balanced posting succeeds and both sides land;
   - an unbalanced posting is rejected and writes nothing (transaction rolls back);
   - balance derivation returns correct values after several postings;
   - a TOPUP-shaped posting (DEBIT system, CREDIT user.online) produces the right online balance.

Do NOT build top-up, pouch, or any endpoint yet — this is the engine the later PRs call.
Open the PR describing the posting API and the invariants the tests enforce.
```

---

## Standing reminders for every task

- One PR per task; keep them small and reviewable. Stop and ask if scope is unclear — don't guess on money logic.
- Money tests (Testcontainers, real Postgres) are part of the PR, not a follow-up.
- Never push to main; never commit as the AI — commits are authored by your GitHub account.
- If a request seems to conflict with a money-safety invariant (CLAUDE.md §7), stop and flag it.