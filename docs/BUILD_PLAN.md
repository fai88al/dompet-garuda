# Claude Code Kickoff Brief — Dompet Digital Backend

This is how to hand the project to Claude Code so it builds **incrementally**, one reviewable PR at
a time, instead of generating the whole MVP in one blob. Paste **Task 1** as your first message.
Do not paste the PRD with "build this" — that invites over-building. Claude Code reads `CLAUDE.md`
automatically; point it at the PRD for *scope*, not as a build list.

**Before you start:** confirm the package root in CLAUDE.md §2 (`id.dompetdigital.backend`) is the
value you want, your DB volume is reset to empty (so Flyway owns the schema), and your GitHub
account has push access to the client repo.

---

## Task 1 — Project scaffold ONLY (first PR)

> Paste this as the first instruction:

```
Read CLAUDE.md fully first. Then scaffold the project skeleton ONLY — no business
endpoints yet. Work on a branch `feat/scaffold` and open a PR against main; do not push to main.

Scope of this task:
1. Maven Spring Boot 3.x project, Java 21, package root id.dompetdigital.backend.
   Include the Maven wrapper (./mvnw).
2. Dependencies: spring-boot-starter-web, -data-jpa, -validation, -actuator;
   flyway-core + flyway-database-postgresql; postgresql driver;
   shedlock-spring + shedlock-provider-jdbc-template;
   org.eclipse.paho client; Testcontainers (postgresql, junit-jupiter).
3. Create the package layout from CLAUDE.md §5 (empty packages with package-info.java
   where there's nothing yet). Don't put logic in them.
4. Two Spring profiles per CLAUDE.md §3:
   - application-api.yml: web enabled, spring.flyway.enabled=true, ddl-auto=validate.
   - application-worker.yml: spring.main.web-application-type=none,
     spring.flyway.enabled=false, ddl-auto=validate, scheduling enabled.
   - application.yml: shared datasource via env vars (${SPRING_DATASOURCE_URL},
     ${SPRING_DATASOURCE_USERNAME}, ${SPRING_DATASOURCE_PASSWORD}); no hardcoded secrets.
5. Move the existing V1__init.sql to src/main/resources/db/migration/V1__init.sql unchanged.
6. Add .gitignore (target/, .env, *.log, .idea/, *.iml) and a committed .env.example.
7. One Testcontainers smoke test that boots the Spring context against a fresh Postgres,
   confirms Flyway applied V1, and asserts a known table (e.g. ledger_entries) exists.

Acceptance:
- ./mvnw clean verify passes.
- Running the api profile against an empty Postgres applies V1 and /actuator/health is UP.
- No business/domain logic yet. Keep the PR small.
Open the PR with a description of what you scaffolded and how to run it.
```

Review that PR and merge it before moving on. Confirm it boots against your local Postgres.

---

## Build order after scaffold (one PR each — don't batch)

Hand these out one at a time, reviewing each before the next. Rationale for the order: the
**ledger is the foundation**, so it comes before anything that moves money; the API service
(Phase 4) is finished before the worker (Phase 5).

**API service (Phase 4)**
1. **PR2 — Auth + device registration (FR1).** Admin token auth; admin-initiated device registration storing the Ed25519 public key; device-token issue + hash; enforce max 3 devices/user. (CLAUDE.md §4)
2. **PR3 — Ledger core (the heart).** Double-entry posting service + balance derivation, plain SQL/JdbcTemplate, in one DB transaction. Tests assert credits = debits and correct balances. *Nothing that moves money is built before this.*
3. **PR4 — Online top-up (FR2).** Admin tops up a user; posts `TOPUP` via the ledger service.
4. **PR5 — Pouch provisioning + certificate (FR3, FR13).** Debit online, issue signed cert (24h expiry), enforce one active cert per device.
5. **PR6 — Sync ingest endpoint (FR5).** Device-auth, write raw batch to `sync_inbox`, return 202. No validation/settlement here. *This completes the API service.*

**Worker service (Phase 5)**
6. **PR7 — Worker bootstrap + inbox poller.** `FOR UPDATE SKIP LOCKED` poll loop, ShedLock on the schedule, status transitions on `sync_inbox`.
7. **PR8 — Settlement (FR4, FR6, FR7, FR8, FR11, FR12 + §9a).** Verify Ed25519 signatures + counters, enforce pouch limit, post `OFFLINE_TRANSFER` (credit receiver online), refund unspent (`POUCH_REFUND`), flag late/over-limit/malformed. The required test cases in CLAUDE.md §10 live here.
8. **PR9 — Reconciliation job (FR9).** Scheduled, ShedLock-wrapped, writes mismatches to flagged.
9. **PR10 — MQTT publisher.** Paho client; publish `sync-result` per CLAUDE.md §6. (Receive-side/status optional.)
10. **PR11 — Admin read endpoints (FR10).** Read-only lists; UI deferred.

**Parallel, anytime after PR6: device simulator.** A small standalone program that generates real
Ed25519-signed batches and calls the sync API — this is what lets you test PR7–PR9 without ESP32
hardware (PRD R1). Worth doing early.

---

## Standing reminders for every task

- One PR per task; keep them small and reviewable. Stop and ask if scope is unclear — don't guess on money logic.
- Money tests (Testcontainers, real Postgres) are part of the PR, not a follow-up.
- Never push to main; never commit as the AI — commits are authored by your GitHub account.
- If a request seems to conflict with a money-safety invariant (CLAUDE.md §7), stop and flag it.