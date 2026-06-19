# CLAUDE.md — Dompet Digital Backend

> Context file for Claude Code. Read this fully before generating or editing any code.
> This is a **payment system**. The money-safety invariants in this file are non-negotiable.
> If a requested change would violate one, stop and flag it instead of implementing it.

---

## 1. What this project is

Dompet Digital is the backend for an **offline-capable IoT payment device** (ESP32-based hardware
wallet). Users top up an online balance, move a capped amount into a device-held "offline pouch",
and then transact **device-to-device over Bluetooth with no internet**. When a device reconnects,
it uploads a signed transaction log that the backend validates and posts to a ledger.

**Stage: prototype.** Goal is a working end-to-end demo, not a production-hardened product.
Prefer simple, correct, auditable code over cleverness or premature scale.

---

## 2. Tech stack (fixed — do not substitute without being asked)

- **Language:** Java 21 (LTS)
- **Framework:** Spring Boot 3.x
- **Build:** Maven (`./mvnw`). If the team later moves to Gradle, ask first.
- **DB:** PostgreSQL 16
- **Migrations:** Flyway. `spring.jpa.hibernate.ddl-auto=validate` — Hibernate must NEVER create or alter schema.
- **Persistence:** Spring Data JPA for simple reads; **plain SQL / JdbcTemplate for all ledger and money writes** (we want the exact SQL visible and reviewable).
- **MQTT client:** Eclipse Paho (`org.eclipse.paho`).
- **Scheduled-job locking:** ShedLock (Postgres-backed) on every `@Scheduled` method.
- **Tests:** JUnit 5 + Testcontainers (real Postgres) for anything touching money.

> **Package root:** `com.dompetdigital.api`. This is the agreed default — **confirm it before
> scaffolding.** If the client's GitHub org has a group-id convention (e.g. `com.<client>...`),
> change it here first; everything else follows this value.

### Migration ownership (important — prevents two migrators racing)

- **API service:** `spring.flyway.enabled=true`, `ddl-auto=validate`. The API is the **only**
  service that runs migrations; it applies them on boot.
- **Worker service:** `spring.flyway.enabled=false`, `ddl-auto=validate`. It validates against the
  existing schema and never migrates. In the Compose stack the worker must start **after** the API
  has migrated (use `depends_on` / a healthcheck gate); on first boot, start the API first.

---

## 3. Architecture (decided — build to this, don't reinvent)

**One codebase, one Docker image, two runtime containers** distinguished only by Spring profile:

- **`api` profile** — REST endpoints enabled, all `@Scheduled` jobs disabled. Fast, stateless, user-facing.
- **`worker` profile** — REST disabled (`spring.main.web-application-type=none`), scheduled jobs + inbox poller enabled. Does the heavy, slow, money-critical work.

This isolates failures: a crash or slow run in reconciliation can never take down the live API.
Do **NOT** split this into two repositories — the ledger/domain code is shared and must never drift.

### The sync flow (transactional inbox pattern)

1. Device uploads a signed transaction batch to the API.
2. API authenticates the device, persists the **raw batch** into `sync_inbox`, returns **`202 Accepted`**. No ledger writes happen in the request thread.
3. Worker polls `sync_inbox` using `SELECT ... FOR UPDATE SKIP LOCKED`, validates signatures, posts ledger entries, marks the row done/failed.
4. Worker notifies the device of the outcome via MQTT topic `wallet/{deviceId}/sync-result`.

Postgres is the job queue — **do not add Kafka/RabbitMQ.**

### The offline pouch model

- Online balance lives in the ledger on the server.
- To go offline, the device requests a pouch top-up: the server **debits the online balance immediately** and issues a signed **certificate** authorizing the device to hold up to its issued amount offline.
- From the server's view that money is already spent. Worst-case fraud is therefore **capped at the pouch size.**
- Offline transactions are signed by both devices (sender signs, receiver countersigns) and carry a per-device **monotonic counter**.

### Ledger posting reference (how each transaction type balances)

| Type | Debit | Credit |
|------|-------|--------|
| `TOPUP` | system account | user.online |
| `POUCH_LOAD` | user.online | device.pouch |
| `OFFLINE_TRANSFER` | sender.pouch | **receiver.online** (never a pouch — enforces no offline re-spend) |
| `POUCH_REFUND` | device.pouch | user.online (unspent amount, at sync) |

---

## 4. Authentication (prototype-grade — NG1; revisit before production)

Transport auth here is intentionally simple. **The real integrity of money lives in the Ed25519
signatures inside each offline transaction and the `(sender_device_id, counter)` constraint — not
in the HTTP layer.** Do not over-engineer this.

- **Admin auth:** a static token from config (`ADMIN_API_TOKEN`) presented as a Bearer token (or HTTP Basic, single admin). Protects admin endpoints: device registration, top-up, and the read-only dashboard endpoints.
- **Device registration is admin-initiated.** The admin registers a device (supplying the owning user + the device's Ed25519 **public key**). The server creates the device and returns a **device API token once**; it stores only a hash of that token. The token is then provisioned onto the device.
- **Device auth:** the device presents its device token as a Bearer token on device endpoints (pouch-load, sync upload). The server looks up the device by token hash.
- **Never** trust transport auth as the anti-double-spend mechanism. A device token proves "this call came from a provisioned device"; it does not authorize any specific money movement. That authorization comes from the signed batch contents validated by the worker.

---

## 5. Module layout

```
src/main/java/id/dompetdigital/backend/
  common/          # shared by api + worker: entities, ledger posting, crypto (Ed25519) verification, DTOs
  config/          # ApiConfig, WorkerConfig (profile-gated beans), SecurityConfig, MqttConfig
  auth/            # device + admin authentication, device-token issuing/hashing
  device/          # device registration, public-key storage, certificate issuance
  wallet/          # online balance, top-up, pouch provisioning
  ledger/          # double-entry posting, balance derivation (the heart — treat with care)
  sync/            # api side: ingest controller -> sync_inbox
                   # worker side: inbox poller + settlement
  reconciliation/  # worker: periodic pouch-vs-ledger reconciliation, flag mismatches
  mqtt/            # Paho client, topic publishers/subscribers
src/main/resources/
  db/migration/    # Flyway V1__init.sql, V2__..., never edit an applied migration
  application.yml          # shared config
  application-api.yml      # api profile
  application-worker.yml   # worker profile
src/test/java/...          # mirror of main; Testcontainers-backed money tests
```

---

## 6. Configuration & local development

- **No secrets in committed files.** `application*.yml` reference environment variables via `${...}`; real values come from the environment / a gitignored `.env`. Commit a `.env.example` with blank or placeholder values so the shape is documented.
- **Datasource (dev):** `localhost:5432`, database `dompet`, user `dompet`, password from `SPRING_DATASOURCE_PASSWORD` (or `POSTGRES_PASSWORD`). Run a local Postgres via the project's `docker-compose.yml` for development; the VPS database is for deployment, reached only over an SSH tunnel.
- **`.gitignore` must include:** `target/`, `.env`, `*.log`, IDE files (`.idea/`, `*.iml`).
- Config values that aren't secret (ports, pool sizes, pouch limit, cert TTL) may have sane defaults in `application.yml`, overridable by env.

---

## 7. MONEY-SAFETY INVARIANTS (read twice)

These are the rules that keep balances correct. Violating any of these is a defect, not a style choice.

1. **No mutable balance column as source of truth.** A user's balance is `SUM` of their ledger entries (credits minus debits). You may keep a cached/materialized balance for reads, but the ledger is authoritative.
2. **Every money movement is balanced double-entry** — for each `ledger_transactions` row, `SUM(amount) WHERE direction='CREDIT'` equals `SUM(amount) WHERE direction='DEBIT'` — written in **one DB transaction**. Never write one side without the other.
3. **Money is `BIGINT`, in whole Rupiah (IDR has no sub-unit in practice).** Never `float`/`double`/`Float`/`Double` for money; use `long` in Java and `BIGINT` in SQL.
4. **Idempotency at the database level.** Offline transactions carry `UNIQUE (sender_device_id, counter)`; replays are rejected by the DB, not just app logic. The sync endpoint must be safe to call twice with the same batch.
5. **The API never posts to the ledger from sync.** Sync ingest only validates auth + writes to `sync_inbox` + returns 202. The **worker is the only writer** of settled offline transactions.
6. **Pouch outflows can never exceed the signed certificate**, and a pouch balance can never go negative. The worker enforces this; a violation means tampering → flag, don't post.
7. **All `@Scheduled` jobs are wrapped with ShedLock** so they run exactly once even with multiple worker instances.
8. **MQTT carries no financial authority.** It is for notifications and status only. Never move, credit, or debit money in response to an MQTT message. Treat all MQTT input as untrusted hints.
9. **Never log secrets:** no PINs, private keys, full signatures, device tokens, or full auth tokens in logs. Redact.
10. **Schema only changes via Flyway.** Never rely on Hibernate auto-DDL. Never edit a migration that has already been applied — add a new one.
11. **Failed/suspicious work is flagged, never silently dropped.** Bad batches go to a failed/flagged state with a reason; they do not disappear.

---

## 8. MQTT topic contract

- `wallet/{deviceId}/status` — device → broker, **retained, QoS 1.** Online/offline + battery. Last-Will sets this to `offline` on disconnect.
- `wallet/{deviceId}/sync-result` — worker → device, QoS 1. Outcome of a submitted sync batch (`settled` / `flagged` + reason).
- `wallet/{deviceId}/cert-refresh` — worker → device, QoS 1. Hint that a new pouch certificate is available to pull (over HTTPS, not MQTT).
- **ACL:** every device may publish and subscribe **only** under `wallet/{itsOwnDeviceId}/#`. Enforced in Mosquitto ACL. One compromised device must not see or spoof another's topics.
- **Transport:** TLS on port 8883 only. Plain 1883 is never exposed.

---

## 9. Commands

```bash
./mvnw clean verify                 # build + run all tests (must pass before any PR)
./mvnw spring-boot:run -Dspring-boot.run.profiles=api      # run API locally
./mvnw spring-boot:run -Dspring-boot.run.profiles=worker   # run worker locally
docker compose up -d                # local stack (currently: postgres; mosquitto/caddy/api/worker added as built)
docker compose logs -f postgres
```

> The Compose file currently defines **postgres only**. Mosquitto, Caddy, and the api/worker
> containers are added as those pieces are built — don't assume they exist yet.

---

## 10. Testing expectations

- Money logic is **not** "looks right" — it is tested. Every ledger operation has a test asserting entries balance (credits = debits) and balances are correct.
- Required test cases for sync settlement: happy path, **replayed batch (must be idempotent)**, **over-pouch-limit (must be flagged, not posted)**, malformed/poison batch (must fail gracefully, not crash the worker), and out-of-order counter.
- Use Testcontainers (real Postgres) for repository/ledger tests — do not mock the database for money logic.
- A failing or skipped money test blocks merge.

---

## 11. Git workflow

- This repo lives in the **client's** GitHub org. You have collaborator access via the human developer's own account — **never use the client's credentials**.
- **Commits must be authored by the human developer's GitHub account, never the AI's.** Verify `git config user.email` matches the human's GitHub email before committing.
- **Never** push directly to `main`. **Never** force-push a shared branch.
- Work on a feature branch (`feat/...`, `fix/...`), commit in logical units with clear messages, push, and **open a PR against `main`** with a description explaining what and why. A human reviews and merges.

---

## 12. What NOT to do

- Don't scaffold a generic CRUD app — build to the architecture in §3.
- Don't add microservices, message brokers, or a service mesh. Modular monolith, two profiles.
- Don't introduce an ORM-generated query for any money movement.
- Don't expand scope beyond the PRD's in-scope list. If something seems missing, ask; it may be an intentional non-goal.
- Don't store or transmit money decisions over MQTT.
- Don't invent an authentication scheme — use §4.
- Don't optimize for scale we don't have (single 8 GB box, prototype). Optimize for correctness and readability.