# CLAUDE.md — Dompet Digital Backend

> Context file for Claude Code. Read this fully before generating or editing any code.
> This is a **payment system**. The money-safety invariants in §7 are non-negotiable.
> If a requested change would violate one, stop and flag it instead of implementing it.

---

## 1. What this project is

Dompet Digital is the backend for an **offline-capable IoT payment device** (ESP32-based hardware
wallet). Users top up an online balance, move a capped amount into a device-held "offline pouch",
and then transact **device-to-device over Bluetooth with no internet**. When a device reconnects,
it uploads a signed transaction log that the backend validates and posts to a ledger.

The device's three core user actions: **Cek Saldo** (check balance), **Transfer** (offline BLE),
and **Scan QR** (QRIS-style). Everything else is the machinery that makes those trustworthy.

**Stage: prototype.** Prefer simple, correct, auditable code over cleverness or premature scale.

---

## 2. Tech stack (fixed — do not substitute without being asked)

- **Language:** Java 21 (LTS)
- **Framework:** Spring Boot 3.x
- **Build:** Maven (`./mvnw`). If the team later moves to Gradle, ask first.
- **DB:** PostgreSQL 16
- **Migrations:** Flyway. `spring.jpa.hibernate.ddl-auto=validate` — Hibernate must NEVER create or alter schema.
- **Persistence:** Spring Data JPA for simple reads; **plain SQL / JdbcTemplate for all ledger and money writes.**
- **MQTT client:** Eclipse Paho (`org.eclipse.paho`).
- **Scheduled-job locking:** ShedLock (Postgres-backed) on every `@Scheduled` method.
- **Tests:** JUnit 5 + Testcontainers (real Postgres) for anything touching money.
- **API docs:** Springdoc OpenAPI (`springdoc-openapi-starter-webmvc-ui`). Swagger UI at `/swagger-ui.html`, api profile only.

> **Package root:** `com.dompetgaruda.api`. Everything follows this value.

### Migration ownership

- **API service:** `spring.flyway.enabled=true`, `ddl-auto=validate`. Runs migrations on boot.
- **Worker service:** `spring.flyway.enabled=false`, `ddl-auto=validate`. Never migrates.
  In compose, worker `depends_on` api with `condition: service_healthy`.

### Spring Security autoconfiguration (REQUIRED)

`UserDetailsServiceAutoConfiguration` must be **excluded** in the API profile. If it is active,
Spring registers a default in-memory user with a rotating random password, creating an unintended
auth path alongside the custom `AdminTokenFilter`. Add to `application-api.yml`:

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
```

Verify it is disabled: `docker compose logs api | grep -i "security password"` must return nothing.

---

## 3. Architecture (decided — build to this, don't reinvent)

**One codebase, one Docker image, two runtime containers** distinguished only by Spring profile:

- **`api` profile** — REST endpoints enabled, all `@Scheduled` jobs disabled. Runs Flyway.
- **`worker` profile** — `spring.main.web-application-type=none`, scheduled jobs enabled, Flyway disabled.

### Profile isolation rule (learned in production — do not repeat this bug)

Any Spring bean that references admin config (`ADMIN_API_TOKEN`, admin security filters,
admin-specific services) **must be annotated `@Profile("api")`** so it never loads in the
worker profile. The worker must start cleanly with no reference to `ADMIN_API_TOKEN` in its logs.
If a class injects `@Value("${admin.api-token}")` without a `@Profile("api")` annotation,
the worker will fail at startup with `PlaceholderResolutionException`. This is a hard rule.

### Expected worker behavior before PR7

Until the inbox poller is added in PR7, the worker has no long-running task. It starts cleanly
and then exits with code 0 — this causes Docker's `restart: unless-stopped` to restart it
every 30 seconds. **This restart loop is expected and not a bug.** It resolves when the
`@Scheduled` inbox poller is added in PR7, which keeps the JVM alive. Do not add a dummy
scheduled task as a workaround.

### The sync flow (transactional inbox pattern)

1. Device uploads signed batch → API writes to `sync_inbox`, returns `202`. No ledger writes.
2. Worker polls `sync_inbox` with `SELECT ... FOR UPDATE SKIP LOCKED`.
3. Worker validates, posts ledger entries, publishes MQTT result.

### Ledger posting reference

| Type | Debit | Credit |
|------|-------|--------|
| `TOPUP` | SYSTEM | user.ONLINE |
| `POUCH_LOAD` | user.ONLINE | device.POUCH |
| `OFFLINE_TRANSFER` | sender.POUCH | receiver.ONLINE |
| `POUCH_REFUND` | device.POUCH | user.ONLINE |

*(Balance enquiry is a read — not in this table. It moves no money.)*

---

## 4. Authentication (prototype-grade — NG1)

- **Admin auth:** static `ADMIN_API_TOKEN` from env, Bearer token, `@Profile("api")` only.
- **Device registration:** admin-initiated. Server returns device token once, stores only hash.
- **Device auth:** device presents token as Bearer on device endpoints.
- **Never** trust transport auth as the anti-double-spend mechanism.

---

## 5. Module layout

```
src/main/java/com/dompetgaruda/api/
  common/          # entities, ledger posting, Ed25519 verification, DTOs
  config/          # ApiConfig, WorkerConfig (@Profile-gated), SecurityConfig, MqttConfig
  auth/            # AdminTokenFilter (@Profile("api")), DeviceTokenService, DeviceTokenVerifier
  device/          # device registration, certificate issuance
  wallet/          # balance enquiry (read), top-up, pouch provisioning
  ledger/          # LedgerPostingService — double-entry posting, balance derivation
  sync/            # api: ingest controller → sync_inbox / worker: inbox poller + settlement
  reconciliation/  # worker: pouch-vs-ledger reconciliation job
  mqtt/            # Paho client, topic publishers/subscribers
src/main/resources/
  db/migration/    # V1__init.sql, V2__... — never edit applied migrations
  application.yml
  application-api.yml    # flyway enabled, swagger enabled, UserDetailsService excluded
  application-worker.yml # no web server, flyway disabled, swagger disabled
```

---

## 6. Configuration & local development

- **No secrets in committed files.** Use `${ENV_VAR}` placeholders. Commit `.env.example`.
- **Datasource (dev):** `localhost:5432` — either SSH tunnel to VPS or local Docker Postgres.
- **CI/CD:** GitHub Actions pipeline deploys on push to `main`. Three jobs: test → build-push → deploy.
  - test: `./mvnw clean verify`
  - build-push: builds Docker image, pushes to GHCR (`ghcr.io/fai88al/dompet-garuda`)
  - deploy: SSHes to VPS, runs `git pull && docker compose -f docker-compose.prod.yml pull && up -d`
- **Compose files:**
  - `docker-compose.yml` — local dev only (Postgres on `127.0.0.1:5434`)
  - `docker-compose.prod.yml` — VPS deployment (api + worker + postgres + caddy when added)
  - Both files are in the repo. The VPS file is NEVER manually maintained — `git pull` keeps it current.
- **Deploy SSH key:** a dedicated `github-actions-deploy` Ed25519 key is in `~/.ssh/authorized_keys`
  on the VPS, separate from the developer's personal key. The private key is stored as the
  `VPS_SSH_KEY` GitHub Actions secret.
- **`.gitignore` must include:** `target/`, `.env`, `*.log`, `.idea/`, `*.iml`

---

## 7. MONEY-SAFETY INVARIANTS (read twice)

1. **No mutable balance column.** Balance = `SUM(CREDIT) − SUM(DEBIT)` over ledger entries.
2. **Every money movement is balanced double-entry** in one DB transaction.
3. **Money is `BIGINT` (whole Rupiah).** Never `float`/`double`/`Float`/`Double`.
4. **Idempotency at DB level.** `UNIQUE(sender_device_id, counter)` rejects replays.
5. **API never posts to ledger from sync.** Only the worker settles offline transactions.
6. **Pouch outflows ≤ certificate.** Violation → flag, never post.
7. **All `@Scheduled` jobs have ShedLock.**
8. **MQTT carries no financial authority.** Treat all MQTT input as untrusted hints.
9. **Never log secrets:** no PINs, private keys, signatures, tokens.
10. **Schema only via Flyway.** Never edit an applied migration — add a new one.
11. **Failed/suspicious work is flagged, never silently dropped.**

---

## 8. MQTT topic contract

- `wallet/{deviceId}/status` — device → broker, retained, QoS 1. Last-Will = `offline`.
- `wallet/{deviceId}/sync-result` — worker → device, QoS 1.
- `wallet/{deviceId}/cert-refresh` — worker → device, QoS 1.
- **ACL:** each device may only pub/sub under `wallet/{itsOwnDeviceId}/#`.
- **Transport:** TLS port 8883 only. Never plain 1883.

---

## 9. Commands

```bash
./mvnw clean verify                                          # must pass before any PR
./mvnw spring-boot:run -Dspring-boot.run.profiles=api        # run API locally
./mvnw spring-boot:run -Dspring-boot.run.profiles=worker     # run worker locally
docker compose up -d                                         # local dev stack (postgres)
docker compose -f docker-compose.prod.yml ps                 # check VPS stack status
docker compose -f docker-compose.prod.yml logs api --tail=50 # api logs on VPS
```

**Deployed stack** (VPS, `docker compose -f docker-compose.prod.yml`):
- `dompet-postgres` — healthy
- `dompet-api` — healthy (Flyway runs here, REST endpoints live here)
- `dompet-worker` — running; restarts until PR7 adds the inbox poller (expected, not a bug)

---

## 10. Testing expectations

- Every ledger operation has a test asserting entries balance (credits = debits).
- **Read-only endpoints** (e.g. balance enquiry) must have a test asserting **zero rows written**
  to `ledger_entries` and `ledger_transactions` before and after the call.
- Required test cases for sync settlement: happy path, replayed batch, over-pouch-limit,
  malformed batch, out-of-order counter.
- Use Testcontainers (real Postgres) — do not mock the database for money logic.
- A failing or skipped money test blocks merge.

---

## 11. Git workflow

- Repo lives in the **client's** GitHub org. Use your own account — never the client's credentials.
- **Commits must be authored by the human developer's GitHub account.**
- **Never** push directly to `main`. **Never** force-push a shared branch.
- Feature branch (`feat/...`, `fix/...`, `docs/...`) → PR against `main` → human reviews → merge.
- `./mvnw clean verify` must pass before opening a PR.

---

## 12. What NOT to do

- Don't scaffold a generic CRUD app.
- Don't add microservices, message brokers, or a service mesh.
- Don't use ORM-generated queries for money movements.
- Don't expand scope beyond the PRD — raise questions first.
- Don't turn balance enquiry into transaction history (NG7).
- Don't store or transmit money decisions over MQTT.
- Don't invent an auth scheme — use §4.
- Don't add a dummy `@Scheduled` task to keep the worker alive — wait for PR7.
- Don't reference `ADMIN_API_TOKEN` in any bean without `@Profile("api")`.
- Don't expose port 8080 on the VPS host — `expose:` only, Caddy will proxy later.
- Don't include you as contributor. Always use my github profile as contributot

---

## 13. Documentation deliverables (required per PR — not optional)

Every PR that introduces or changes at least one endpoint must ship these three artifacts.
A PR missing any of them is incomplete and must not be merged.

### 13a. README.md — kept current, never appended

**Before opening any PR, run this and verify each heading appears exactly once:**
```bash
grep -n "^## " README.md
```
If any heading appears twice, delete the older copy. Never append a new `## Milestones` or
`## API reference` section — update the existing one in place. This is a hard rule because
appending instead of updating has already caused duplicate sections in this repo.

Sections to maintain:
- What this is · Architecture overview · Prerequisites · Quick start
- Running the API · Running the Worker · Running tests
- API reference (summary table — one row per endpoint, updated each PR)
- Environment variables · Project structure · Milestones (tick off per PR)

### 13b. Example API calls — `docs/api-examples/`

One numbered shell script per endpoint. Format: `curl` command + expected response as comment.
Prerequisites stated. Scripts must be copy-pasteable with real placeholder values.

### 13c. Swagger annotations

Every endpoint controller: `@Tag`, `@Operation`, `@ApiResponse` per status code.
Every request/response DTO: `@Schema` per field. Springdoc generates `/swagger-ui.html`.
Swagger enabled on `api` profile only (`springdoc.api-docs.enabled: false` in worker yml).