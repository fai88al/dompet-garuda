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

Interactive docs (requires API server running): `http://localhost:8080/swagger-ui.html`

### Implemented endpoints

All admin endpoints require `Authorization: Bearer <ADMIN_API_TOKEN>`.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/admin/users` | Create a user and open their online ledger account |
| `POST` | `/admin/devices` | Register an ESP32 device; returns device token once |

See `docs/api-examples/` for ready-to-run `curl` commands.

#### POST /admin/users

```bash
curl -s -X POST http://localhost:8080/admin/users \
  -H "Authorization: Bearer dev-admin-token-here" \
  -H "Content-Type: application/json" \
  -d '{"fullName": "Budi Santoso", "phone": "+62811000001"}' | jq .
```

Response `201`:
```json
{
  "userId": "<uuid>",
  "fullName": "Budi Santoso",
  "phone": "+62811000001",
  "status": "ACTIVE",
  "onlineAccountId": "<uuid>",
  "createdAt": "<iso-timestamp>"
}
```

#### POST /admin/devices

The device's Ed25519 public key is provided by the firmware at first setup. The `deviceToken` in the response is shown **once** — provision it onto the device immediately.

```bash
curl -s -X POST http://localhost:8080/admin/devices \
  -H "Authorization: Bearer dev-admin-token-here" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "<uuid-from-create-user>",
    "publicKey": "<base64-ed25519-public-key>",
    "label": "Device 1"
  }' | jq .
```

Response `201`:
```json
{
  "deviceId": "<uuid>",
  "userId": "<uuid>",
  "deviceLabel": "Device 1",
  "pouchAccountId": "<uuid>",
  "registeredAt": "<iso-timestamp>",
  "deviceToken": "<64-char-hex — save this, shown only once>"
}
```

Constraint errors: `404` user not found · `409` public key duplicate · `422` user already has 3 devices.

---

## Environment variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | Yes | — | JDBC URL, e.g. `jdbc:postgresql://localhost:5434/dompet` |
| `SPRING_DATASOURCE_USERNAME` | Yes | — | Database user, e.g. `dompet` |
| `SPRING_DATASOURCE_PASSWORD` | Yes | — | Database password |
| `POSTGRES_PASSWORD` | Yes (Docker) | — | Password for the Docker Postgres container; must match `SPRING_DATASOURCE_PASSWORD` |
| `ADMIN_API_TOKEN` | Yes | — | Static Bearer token protecting all `/admin/**` endpoints |

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
  sync/           # (coming) API ingest + worker settlement
  reconciliation/ # (coming) pouch-vs-ledger reconciliation job
  mqtt/           # (coming) Paho client, topic publishers
  wallet/         # (coming) top-up, pouch provisioning

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

- [x] **Scaffold** — Spring Boot project, Flyway, dual-profile setup, Docker Compose (Postgres)
- [x] **FR1 — Admin auth, user creation, device registration** — `POST /admin/users`, `POST /admin/devices`, Ed25519 public key storage, device token issuance
- [ ] FR2 — Top-up (`POST /admin/topup`)
- [ ] FR3 — Balance enquiry (`GET /device/balance`)
- [ ] FR4 — Pouch load (`POST /device/pouch/load`)
- [ ] FR5 — Sync ingest (`POST /device/sync`) + worker settlement
- [ ] FR6 — MQTT notifications
- [ ] FR7 — Reconciliation job

---

## Milestones

- [x] **Scaffold** — Spring Boot project, Flyway, dual-profile setup, Docker Compose (Postgres)
- [x] **FR1 — Admin auth, user creation, device registration** — `POST /admin/users`, `POST /admin/devices`, Ed25519 public key storage, device token issuance
- [x] **Ledger core** — `LedgerPostingService`: double-entry posting (plain SQL, balanced invariant enforced), balance derivation, SYSTEM/ONLINE/POUCH account helpers; Testcontainers integration tests; Swagger UI on api profile
- [ ] FR2 — Top-up (`POST /admin/topup`)
- [ ] FR3 — Balance enquiry (`GET /device/balance`)
- [ ] FR4 — Pouch load (`POST /device/pouch/load`)
- [ ] FR5 — Sync ingest (`POST /device/sync`) + worker settlement
- [ ] FR6 — MQTT notifications
- [ ] FR7 — Reconciliation job

---

## API reference

Swagger UI (requires API server running): `http://localhost:8080/swagger-ui.html`

No device-facing endpoints yet. The ledger core is internal infrastructure only; public endpoints will be documented here as they ship.

---

## Git workflow

- Never push directly to `main`.
- Work on feature branches (`feat/...`, `fix/...`, `docs/...`), open a PR against `main`.
- All commits must be authored by the human developer's GitHub account.
- `./mvnw clean verify` must pass before opening a PR.
