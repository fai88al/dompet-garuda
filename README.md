# Dompet Digital — Backend

## What this is

Dompet Digital is the backend for an offline-capable IoT payment device (ESP32 hardware wallet). Users top up an online balance on the server, move a capped amount into a device-held "offline pouch", and then transact device-to-device over Bluetooth with no internet connection. When a device reconnects, it uploads a signed transaction log that the backend validates and posts to a double-entry ledger. The device's three on-screen actions — **Cek Saldo** (check balance), **Transfer** (offline BLE), and **Scan QR** (QRIS-style) — are the user-facing surface; everything else is the machinery that makes those actions trustworthy.

---

## Architecture overview

One codebase, one Docker image, two runtime containers distinguished only by Spring profile. The **`api` profile** serves REST endpoints and runs Flyway migrations on boot; all scheduled jobs are disabled. The **`worker` profile** disables the web server and instead runs background jobs: it polls a Postgres-backed sync inbox, validates offline transaction signatures, posts ledger entries, and publishes results over MQTT. This split isolates failures — a slow reconciliation job can never take down the live API — without introducing a separate service or message broker.

---

## Prerequisites

- Java 21+
- Maven 3.x (or use the included `./mvnw` wrapper — no install needed)
- Docker Desktop (for the Postgres container and Testcontainers in tests)
- (Optional) SSH tunnel to a remote Postgres if not running Docker locally

---

## Quick start

```bash
# 1. Clone
git clone <repo-url>
cd dompet-garuda

# 2. Configure environment
cp .env.example .env
# Edit .env — fill in database password and admin token (see Environment variables below)

# 3. Start Postgres
export $(grep -v '^#' .env | xargs)
docker compose up -d
docker compose ps   # wait until postgres is healthy

# 4. Run the API (applies migrations on first boot)
./mvnw spring-boot:run -Dspring-boot.run.profiles=api
```

The API starts on **port 8080**. Swagger UI is available at `http://localhost:8080/swagger-ui.html`.

---

## Running the API

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=api
```

- Flyway applies all pending migrations automatically on startup.
- REST endpoints are live on port 8080.
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health check: `http://localhost:8080/actuator/health`

---

## Running the Worker

Run in a **separate terminal** after the API has started (the API must migrate first on a fresh database):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=worker
```

The worker has no HTTP server. It polls `sync_inbox` for uploaded device batches, validates Ed25519 signatures, posts settled transactions to the double-entry ledger, and notifies devices of the result via MQTT.

---

## Running tests

```bash
./mvnw clean verify
```

Tests use Testcontainers — Docker Desktop must be running. A real Postgres 16 container is spun up automatically; no local database configuration is needed for the test run.

---

## API reference

Swagger UI (requires API server running): `http://localhost:8080/swagger-ui.html`

Admin endpoints require `Authorization: Bearer <ADMIN_API_TOKEN>`. Device endpoints require a device Bearer token.

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `POST` | `/admin/users` | Create a user + open ONLINE ledger account | Admin |
| `POST` | `/admin/devices` | Register a device (returns token once) | Admin |
| `POST` | `/admin/users/{userId}/topup` | Credit user's ONLINE balance (TOPUP posting) | Admin |
| `POST` | `/device/pouch/load` | Load funds into offline pouch; issues signed certificate (FR3/FR13) | Device |
| `GET` | `/device/balance` | Return online balance + pouch committed (FR14) | Device |
| `POST` | `/device/sync` | Upload signed offline transaction batch; stored in sync_inbox (FR5) | Device |

See `docs/api-examples/` for copy-pasteable `curl` examples of every endpoint.

---

## Environment variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | Yes | — | JDBC URL, e.g. `jdbc:postgresql://localhost:5434/dompet` |
| `SPRING_DATASOURCE_USERNAME` | Yes | — | Database user, e.g. `dompet` |
| `SPRING_DATASOURCE_PASSWORD` | Yes | — | Database password |
| `POSTGRES_PASSWORD` | Yes (Docker) | — | Password for the Docker Postgres container; must match `SPRING_DATASOURCE_PASSWORD` |
| `ADMIN_API_TOKEN` | Yes | — | Static Bearer token protecting all `/admin/**` endpoints |
| `SERVER_SIGNING_KEY` | Yes (api) | — | Base64-encoded 32-byte Ed25519 seed for signing offline certificates |
| `POUCH_MAX_AMOUNT_IDR` | Yes | — | Maximum Rupiah amount loadable per pouch provisioning call |

> **Port note (macOS):** the Docker Postgres runs on **5434** to avoid colliding with a Homebrew Postgres on the default 5432.

---

## Project structure

```
src/main/java/com/dompetgaruda/api/
  auth/           # AdminTokenFilter, DeviceTokenService, DeviceTokenVerifier
  common/         # JPA entities (User, Device, Account), repositories
  config/         # SecurityConfig (stateless Bearer-token auth)
  device/         # AdminController, AdminService, DTOs
  ledger/         # LedgerPostingService — double-entry posting (plain SQL), balance derivation, account helpers
  sync/           # api: SyncIngestController → sync_inbox; worker: inbox poller + settlement (PR7/PR8)
  wallet/         # WalletController (top-up), PouchController (pouch load), DeviceBalanceController (balance)
  reconciliation/ # (coming PR9) pouch-vs-ledger reconciliation job
  mqtt/           # (coming PR10) Paho client, topic publishers

src/main/resources/
  db/migration/          # Flyway migrations (V1__init.sql, …) — never edit applied files
  application.yml        # shared config (datasource, actuator, pool sizes)
  application-api.yml    # api profile: Flyway enabled, Swagger enabled
  application-worker.yml # worker profile: no web server, Flyway disabled, Swagger disabled

docs/api-examples/       # curl scripts for every endpoint
```

---

## Database schema overview

| Table | Purpose |
|-------|---------|
| `users` | Account holders |
| `devices` | ESP32 wallets bound to a user, with Ed25519 public key |
| `accounts` | Ledger accounts: one `SYSTEM`, one `ONLINE` per user, one `POUCH` per device |
| `ledger_transactions` | Journal entries grouping balanced postings |
| `ledger_entries` | Immutable double-entry postings (append-only) |
| `offline_certificates` | Server-signed authorisation for an offline pouch (24 h expiry) |
| `sync_inbox` | Raw device-uploaded batches; Postgres-backed worker job queue |
| `offline_transactions` | Settled BLE transfers with replay-protection unique constraint |
| `flagged_transactions` | Anomalies from settlement/reconciliation |
| `shedlock` | Distributed job lock for worker `@Scheduled` jobs |

---

## Milestones

> FR numbers reference the PRD (`docs/PRD.md`).

- [x] **PR1 — Scaffold** — Spring Boot project, Flyway, dual-profile setup, Docker Compose (Postgres)
- [x] **PR2 — FR1 — Admin auth, user creation, device registration** — `POST /admin/users`, `POST /admin/devices`, Ed25519 public key storage, device token issuance
- [x] **PR3 — Ledger core** — `LedgerPostingService`: double-entry posting (plain SQL, balanced invariant enforced), balance derivation, SYSTEM/ONLINE/POUCH account helpers; Testcontainers integration tests; Swagger UI on api profile
- [x] **PR4 — FR2 — Top-up** — `POST /admin/users/{userId}/topup`, double-entry TOPUP posting (DEBIT system → CREDIT user.online), ledger-derived balance returned
- [x] **PR4b — FR14 — Balance enquiry / Cek Saldo** — `GET /device/balance`, device Bearer token auth, online balance derived from ledger SUM, pouch committed from active certificate; zero ledger writes enforced by test
- [x] **PR5 — FR3/FR13 — Pouch provisioning** — `POST /device/pouch/load` — debit user.online → credit device.pouch, Ed25519-signed offline certificate; 409 on duplicate active cert
- [x] **PR6 — FR5 — Sync ingest** — `POST /device/sync` — stores signed batch in sync_inbox (PENDING), returns 202 immediately; synced_after_expiry flagged when cert expired; zero ledger writes enforced by test
- [x] **PR7 — Worker bootstrap + inbox poller** — scheduled job polls sync_inbox (SELECT … FOR UPDATE SKIP LOCKED), keeps worker JVM alive
- [x] **PR8 — FR4/FR6/FR7/FR8/FR11/FR12 — Settlement** — Ed25519 signature verify per transaction, OFFLINE_TRANSFER + POUCH_REFUND double-entry postings, COUNTER_REPLAY/BAD_SIGNATURE/OVER_LIMIT/MALFORMED flagging; 10 Testcontainers integration tests
- [ ] **PR9 — Reconciliation** — periodic pouch-vs-ledger check, flag mismatches via flagged_transactions
- [ ] **PR10 — MQTT** — Paho client, cert-refresh hints, sync-result publish over TLS 8883
- [ ] **PR11 — Admin read endpoints** — dashboard / user / device lookup

---

## Git workflow

- Never push directly to `main`.
- Work on feature branches (`feat/...`, `fix/...`, `docs/...`), open a PR against `main`.
- All commits must be authored by the human developer's GitHub account.
- `./mvnw clean verify` must pass before opening a PR.
