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

The device's three core user actions mirror its on-screen menu: **Cek Saldo** (check balance),
**Transfer** (offline BLE), and **Scan QR** (QRIS-style). The rest of the system is the machinery
that makes those trustworthy.

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
- **API docs:** Springdoc OpenAPI (`springdoc-openapi-starter-webmvc-ui`). Swagger UI at `/swagger-ui.html`, enabled on the `api` profile only — never on the worker.

> **Package root:** `com.dompetgaruda.api`. Everything follows this value.

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

### Two balance views (online vs offline) — matters for "Cek Saldo"

A device's balance is not a single number; be precise about which one you return:

- **Online balance (authoritative):** `SUM` of the user's ONLINE-account ledger entries (credits − debits). Computed server-side; the source of truth.
- **Pouch committed:** when a pouch is loaded, that amount moves from the online account into the device's POUCH account. Server-side, the POUCH balance equals the active certificate's issued amount. **The server does NOT see offline spends against the pouch until the device syncs.**
- The **balance-enquiry endpoint** (FR14) is **read-only** — it returns *online balance* + *pouch committed*, clearly labelled, derived from the ledger. It is a read, never a posting, so it does **not** appear in the posting reference below.
- While offline, only the **device's own local pouch figure** is accurate (firmware-side, not a server call). The server view and the device view reconcile at sync.

### Ledger posting reference (how each transaction type balances)

| Type | Debit | Credit |
|------|-------|--------|
| `TOPUP` | system account | user.online |
| `POUCH_LOAD` | user.online | device.pouch |
| `OFFLINE_TRANSFER` | sender.pouch | **receiver.online** (never a pouch — enforces no offline re-spend) |
| `POUCH_REFUND` | device.pouch | user.online (unspent amount, at sync) |

*(Balance enquiry is a read and is intentionally absent here — it moves no money.)*

---

## 4. Authentication (prototype-grade — NG1; revisit before production)

Transport auth here is intentionally simple. **The real integrity of money lives in the Ed25519
signatures inside each offline transaction and the `(sender_device_id, counter)` constraint — not
in the HTTP layer.** Do not over-engineer this.

- **Admin auth:** a static token from config (`ADMIN_API_TOKEN`) presented as a Bearer token (or HTTP Basic, single admin). Protects admin endpoints: device registration, top-up, and the read-only dashboard endpoints.
- **Device registration is admin-initiated.** The admin registers a device (supplying the owning user + the device's Ed25519 **public key**). The server creates the device and returns a **device API token once**; it stores only a hash of that token. The token is then provisioned onto the device.
- **Device auth:** the device presents its device token as a Bearer token on device endpoints (balance enquiry, pouch-load, sync upload). The server looks up the device by token hash.
- **Never** trust transport auth as the anti-double-spend mechanism. A device token proves "this call came from a provisioned device"; it does not authorize any specific money movement. That authorization comes from the signed batch contents validated by the worker.

---

## 5. Module layout

```
src/main/java/com/dompetgaruda/api/
  common/          # shared by api + worker: entities, ledger posting, crypto (Ed25519) verification, DTOs
  config/          # ApiConfig, WorkerConfig (profile-gated beans), SecurityConfig, MqttConfig
  auth/            # device + admin authentication, device-token issuing/hashing
  device/          # device registration, public-key storage, certificate issuance
  wallet/          # online balance, balance enquiry (read), top-up, pouch provisioning
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
- **Datasource (dev):** `localhost:5432`, database `dompet`, user `dompet`, password from `SPRING_DATASOURCE_PASSWORD` (or `POSTGRES_PASSWORD`). The developer reaches the VPS Postgres over an SSH tunnel that presents it as `localhost:5432`; a local Postgres (same compose) can be used instead — same config, the tunnel is simply off. Only one can hold port 5432 at a time.
- **`.gitignore` must include:** `target/`, `.env`, `*.log`, IDE files (`.idea/`, `*.iml`).
- Config values that aren't secret (ports, pool sizes, pouch limit, cert TTL) may have sane defaults in `application.yml`, overridable by env.

---

## 7. MONEY-SAFETY INVARIANTS (read twice)

These are the rules that keep balances correct. Violating any of these is a defect, not a style choice.

1. **No mutable balance column as source of truth.** A user's balance is `SUM` of their ledger entries (credits minus debits). You may keep a cached/materialized balance for reads, but the ledger is authoritative. The balance-enquiry endpoint (FR14) derives from the ledger; it never reads a hand-maintained balance field.
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
- Balance enquiry (FR14) has a test asserting the returned online figure equals the ledger-derived sum, and that calling it makes **no** ledger writes.
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
- Don't turn balance enquiry into transaction history or statements — return current figures only (PRD NG7).
- Don't store or transmit money decisions over MQTT.
- Don't invent an authentication scheme — use §4.
- Don't optimize for scale we don't have (single 8 GB box, prototype). Optimize for correctness and readability.

---

## 13. Documentation deliverables (required per PR — not optional)

Every PR that introduces or changes at least one endpoint must ship these three artifacts alongside
the code. They are part of the PR, not a follow-up task. A PR that adds endpoints without them
is incomplete.

### 13a. README.md (kept current, cumulative)

`README.md` lives at the repo root and is updated **in the same PR** that adds the feature.
It is the running human-readable guide to the project. Sections to maintain:

```
# Dompet Digital — Backend

## What this is          (one paragraph, non-technical)
## Architecture overview (one paragraph: monolith, two profiles, why)
## Prerequisites         (Java 21, Maven, Docker, SSH tunnel if using VPS Postgres)
## Quick start           (clone → set .env → tunnel or local Postgres → ./mvnw spring-boot:run)
## Running the API       (profile flag, health check URL)
## Running the Worker    (profile flag, what it does)
## Running tests         (./mvnw clean verify)
## API reference         (link to Swagger UI when running + summary table — updated each PR)
## Environment variables (table: name | required | default | description)
## Project structure     (the module layout from CLAUDE.md §5, one line per module)
## Milestones            (what's built, what's coming — tick off per PR)
```

Keep the language plain — someone unfamiliar with the project should be able to run it in under
10 minutes by following README alone.

### 13b. Example API calls (docs/api-examples/)

For every **new or changed endpoint**, add or update a file in `docs/api-examples/`.
Use `curl` as the format — it's universal and requires no tooling. Name files after the feature:

```
docs/api-examples/
  01-create-user.sh
  02-register-device.sh
  03-top-up.sh
  04-balance-enquiry.sh
  05-pouch-load.sh
  06-sync-ingest.sh
  ...
```

Each file must show:
- The exact `curl` command with real-looking placeholder values (not `<YOUR_TOKEN>` — use
  `Bearer dev-admin-token-here` style so it's copy-pasteable).
- The **expected response** as a comment below the command (HTTP status + JSON shape).
- Any prerequisite (e.g. "run 01-create-user.sh first").

These files serve two purposes: they let any team member hit the API without Postman or reading
the code, and they become the integration test script for the demo.

### 13c. Swagger / OpenAPI annotations

Every endpoint controller must have:
- `@Tag(name = "...")` on the controller class grouping it in Swagger UI.
- `@Operation(summary = "...")` on each method — one clear sentence.
- `@ApiResponse` for each HTTP status the method can return (200/201/202/400/401/404/409 etc.)
  with a brief description.
- Request/response DTOs annotated with `@Schema(description = "...")` on each field.

Springdoc will generate the `/v3/api-docs` JSON and serve Swagger UI at `/swagger-ui.html`
automatically. The goal is that Swagger UI alone is sufficient for a team member to understand
and exercise every endpoint without reading source code.

**Swagger is enabled on the `api` profile only.** In `application-worker.yml` set:
```yaml
springdoc:
  api-docs:
    enabled: false
```