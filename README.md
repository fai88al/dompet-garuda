# Dompet Garuda — Backend

Backend for an offline-capable IoT payment device (ESP32 hardware wallet). Users top up an online balance, load a capped amount into a device-held offline pouch, and transact device-to-device over Bluetooth with no internet. When a device reconnects, it uploads a signed transaction log that the backend validates and posts to a double-entry ledger.

**Stage: prototype.** See `CLAUDE.md` for architecture decisions and money-safety invariants.

---

## Tech stack

| Layer | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.4 |
| Build | Maven (`./mvnw`) |
| Database | PostgreSQL 16 |
| Migrations | Flyway (append-only, `ddl-auto=validate`) |
| Persistence | Spring Data JPA (reads) + JdbcTemplate (all ledger writes) |
| MQTT | Eclipse Paho |
| Job locking | ShedLock (Postgres-backed) |
| Tests | JUnit 5 + Testcontainers (real Postgres) |

---

## Prerequisites

- Java 21+
- Maven (or use the included `./mvnw` wrapper)
- Docker Desktop (for Postgres and Testcontainers)

> **macOS note:** if you have a local Homebrew PostgreSQL running on port 5432, the project's Docker container uses **port 5434** to avoid the conflict.

---

## Local setup

### 1. Clone and configure environment

```bash
git clone <repo-url>
cd dompet-garuda

cp .env.example .env
```

Edit `.env` and fill in the values:

```env
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/dompet
SPRING_DATASOURCE_USERNAME=dompet
SPRING_DATASOURCE_PASSWORD=<choose-a-password>

POSTGRES_PASSWORD=<same-password-as-above>

ADMIN_API_TOKEN=<a-strong-random-secret>
```

### 2. Start the database

```bash
# Export env vars so docker-compose picks them up
export $(grep -v '^#' .env | xargs)

docker compose up -d

# Verify Postgres is healthy
docker compose ps
```

### 3. Run the API server

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=api
```

Flyway applies all migrations automatically on first boot. The server starts on **port 8080**.

Health check:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP",...}
```

### 4. Run the worker (separate terminal)

The worker processes the sync inbox, settles offline transactions, and runs reconciliation jobs. It does not expose HTTP.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=worker
```

> Start the API first on a fresh database — it runs Flyway migrations. The worker validates the schema but never migrates.

---

## Running tests

```bash
./mvnw clean verify
```

Tests use Testcontainers to spin up a real Postgres 16 container automatically — no local database needed for the test run. Docker Desktop must be running.

---

## Runtime profiles

| Profile | Web | Flyway | Scheduled jobs | Purpose |
|---|---|---|---|---|
| `api` | ✅ enabled | ✅ runs migrations | ❌ off | User-facing REST API |
| `worker` | ❌ off | ❌ schema-validate only | ✅ on | Background settlement & reconciliation |

---

## Admin API

All admin endpoints require:

```
Authorization: Bearer <ADMIN_API_TOKEN>
```

Requests without the token return `401 Unauthorized`.

### Create a user

```bash
curl -s -X POST http://localhost:8080/admin/users \
  -H "Authorization: Bearer $ADMIN_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"fullName": "Budi Santoso", "phone": "+62811000001"}' | jq .
```

Response:

```json
{
  "userId": "...",
  "fullName": "Budi Santoso",
  "phone": "+62811000001",
  "status": "ACTIVE",
  "onlineAccountId": "...",
  "createdAt": "..."
}
```

### Register a device

The device's Ed25519 public key is provided by the device firmware at first setup. The server returns a **device API token once** — store it securely; it cannot be recovered.

```bash
curl -s -X POST http://localhost:8080/admin/devices \
  -H "Authorization: Bearer $ADMIN_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "<uuid-from-create-user>",
    "publicKey": "<base64-encoded-ed25519-public-key>",
    "label": "Device 1"
  }' | jq .
```

Response:

```json
{
  "deviceId": "...",
  "userId": "...",
  "deviceLabel": "Device 1",
  "pouchAccountId": "...",
  "registeredAt": "...",
  "deviceToken": "<64-char-hex — save this, shown only once>"
}
```

**Constraints enforced:**
- Maximum **3 devices per user** — 4th registration returns `422`
- Duplicate public key — returns `409`
- Non-existent user — returns `404`

---

## Project structure

```
src/main/java/com/dompetgaruda/api/
  auth/           # AdminTokenFilter, DeviceTokenService, DeviceTokenVerifier
  common/         # JPA entities (User, Device, Account), repositories
  config/         # SecurityConfig (stateless Bearer-token auth)
  device/         # AdminController, AdminService, DTOs
  ledger/         # (coming) double-entry posting and balance derivation
  sync/           # (coming) API ingest + worker settlement
  reconciliation/ # (coming) pouch-vs-ledger reconciliation job
  mqtt/           # (coming) Paho client, topic publishers
  wallet/         # (coming) top-up, pouch provisioning

src/main/resources/
  db/migration/   # Flyway migrations (V1__init.sql, V2__...) — never edit applied files
  application.yml              # shared config (datasource, actuator)
  application-api.yml          # api profile (Flyway on, web on)
  application-worker.yml       # worker profile (Flyway off, web off)
```

---

## Database schema overview

| Table | Purpose |
|---|---|
| `users` | Account holders |
| `devices` | ESP32 wallets bound to a user, with Ed25519 public key |
| `accounts` | Ledger accounts: one `SYSTEM`, one `ONLINE` per user, one `POUCH` per device |
| `ledger_transactions` | Journal entries grouping balanced postings |
| `ledger_entries` | Immutable double-entry postings (append-only) |
| `offline_certificates` | Server-signed authorisation for an offline pouch (24 h expiry) |
| `sync_inbox` | Raw device-uploaded batches; worker job queue |
| `offline_transactions` | Settled BLE transfers with replay-protection constraint |
| `flagged_transactions` | Anomalies from settlement/reconciliation |
| `shedlock` | Distributed job lock for worker `@Scheduled` jobs |

---

## Environment variables reference

| Variable | Required | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | ✅ | JDBC URL, e.g. `jdbc:postgresql://localhost:5434/dompet` |
| `SPRING_DATASOURCE_USERNAME` | ✅ | Database user (default: `dompet`) |
| `SPRING_DATASOURCE_PASSWORD` | ✅ | Database password |
| `POSTGRES_PASSWORD` | ✅ (Docker) | Password for the Docker Postgres container |
| `ADMIN_API_TOKEN` | ✅ | Static Bearer token protecting all `/admin/**` endpoints |

---

## Git workflow

- Never push directly to `main`.
- Work on feature branches (`feat/...`, `fix/...`), open a PR against `main`.
- All commits must be authored by the human developer's GitHub account.
- `./mvnw clean verify` must pass before opening a PR.
