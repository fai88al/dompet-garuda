# CLAUDE.md — Dompet Digital Backend

> Context file for Claude Code. Read this fully before generating or editing any code.
> This is a **payment system**. The money-safety invariants in §7 are non-negotiable.
> If a requested change would violate one, stop and flag it instead of implementing it.

---

## 1. What this project is

Dompet Digital (Dompet Garuda) is the backend for an **offline-capable IoT payment device**
(ESP32-based hardware wallet). Users top up an online balance, move a capped amount into a
device-held "offline pouch", and transact **device-to-device over Bluetooth with no internet**.
When a device reconnects, it uploads a signed transaction log that the backend validates and
posts to a ledger.

The product now also supports **online, server-mediated transactions** (new scope — see §14):
direct online transfer between users, and "Bayar QR" — a QR-code-based payment flow, both
online and offline. **"Bayar QR" is NOT the national QRIS standard.** There is no integration
with Bank Indonesia, a bank, or a licensed payment service provider. The QR code is generated
and scanned entirely between Dompet Garuda devices, and the transaction settles inside this
system's own ledger. Never call it "QRIS" in code, docs, or UI copy — always "Bayar QR".

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
- **Password hashing:** Spring Security `BCryptPasswordEncoder`, cost factor 10.
- **JWT:** `io.jsonwebtoken` (jjwt) for admin/writer session tokens.

> **Package root:** `com.dompetgaruda.api`. Everything follows this value.

### Migration ownership

- **API service:** `spring.flyway.enabled=true`, `ddl-auto=validate`. Runs migrations on boot.
- **Worker service:** `spring.flyway.enabled=false`, `ddl-auto=validate`. Never migrates.
  In compose, worker `depends_on` api with `condition: service_healthy`.

### Spring Security autoconfiguration (REQUIRED)

`UserDetailsServiceAutoConfiguration` must be **excluded** in the API profile. Add to
`application-api.yml`:

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

Any Spring bean that references admin config (`ADMIN_JWT_SECRET`, admin security filters,
admin-specific services) **must be annotated `@Profile("api")`** so it never loads in the
worker profile. A class injecting `@Value` without `@Profile("api")` on admin-only config
will crash the worker at startup with `PlaceholderResolutionException`. This is a hard rule.

### The offline sync flow (transactional inbox pattern — unchanged, still authoritative for offline)

1. Device uploads signed batch → API writes to `sync_inbox`, returns `202`. No ledger writes.
2. Worker polls `sync_inbox` with `SELECT ... FOR UPDATE SKIP LOCKED`.
3. Worker validates, posts ledger entries, publishes MQTT result.

### Online flows (new — see §14) do NOT use the inbox pattern

Online transfer and Bayar QR Online are synchronous, server-mediated operations. The API
validates and posts directly to the ledger in the same request — there is no worker
settlement step for these, because there is no offline signing/replay problem to solve.
**This is a deliberate architectural difference from the offline flow — do not force these
into the sync_inbox/worker pattern.**

### Ledger posting reference

| Type | Debit | Credit | Settled by |
|------|-------|--------|------------|
| `TOPUP` | SYSTEM | user.ONLINE | API (synchronous) |
| `POUCH_LOAD` | user.ONLINE | device.POUCH | API (synchronous) |
| `OFFLINE_TRANSFER` | sender.POUCH | receiver.ONLINE | Worker (async, at sync) |
| `POUCH_REFUND` | device.POUCH | user.ONLINE | Worker (async, at sync) |
| `ONLINE_TRANSFER` | sender.ONLINE | receiver.ONLINE | API (synchronous) — **new** |
| `QR_PAYMENT_ONLINE` | payer.ONLINE | payee.ONLINE | API (synchronous) — **new** |

*(Balance enquiry is a read — not in this table. It moves no money.)*

---

## 4. Authentication (prototype-grade — NG1)

- **Admin/writer auth:** real user accounts in `admin_users` table (username + BCrypt password
  hash + role: `ADMIN` or `WRITER`). JWT-based, 24h expiry, `@Profile("api")` only.
- **Device registration:** admin-initiated. Server returns device token once, stores only hash.
- **Device auth:** device presents token as Bearer on device endpoints. Same token is used for
  offline endpoints and the new online endpoints (§14) — no separate credential for online.
- **Never** trust transport auth as the anti-double-spend mechanism for offline flows. For
  online flows, transport auth (Bearer device token) is the primary authorization check, backed
  by the idempotency key for duplicate-submission safety (§14.4).

### Admin/writer login

```
POST /admin/auth/login
Body:    { "username": "...", "password": "..." }
Success: 200 { "token": "<JWT>", "type": "Bearer", "username": "...", "role": "ADMIN"|"WRITER" }
Failure: 401 { "message": "Invalid username or password" }
```

Passwords are BCrypt-hashed. JWT signed with `ADMIN_JWT_SECRET` (env var, 32-byte hex).
Brute-force protection: 5 failed attempts / 5 min / IP → 429. `ADMIN_API_TOKEN` is retired —
never reintroduce it. New accounts are seeded via Flyway or a future admin-only endpoint —
no public signup.

---

## 5. Module layout

```
src/main/java/com/dompetgaruda/api/
  common/          # entities, ledger posting, Ed25519 verification, DTOs
  config/          # ApiConfig, WorkerConfig (@Profile-gated), SecurityConfig, MqttConfig
  auth/            # AdminTokenFilter (@Profile("api"), JWT), DeviceTokenService,
                   # DeviceTokenVerifier, AdminLoginController, AdminUser entity/repository
  device/          # device registration, certificate issuance, device status admin endpoints
  wallet/          # balance enquiry (read), top-up, pouch provisioning
  ledger/          # LedgerPostingService — double-entry posting, balance derivation
  transfer/        # NEW — online transfer between users (§14.1)
  qrpayment/       # NEW — Bayar QR online + offline payment requests (§14.2, §14.3)
  sync/            # api: ingest controller → sync_inbox / worker: inbox poller + settlement
  reconciliation/  # worker: pouch-vs-ledger reconciliation job
  mqtt/            # Paho client, topic publishers/subscribers
  admin/           # admin-only actions: resolve flags, etc.
  article/         # article CRUD, public read endpoints (WRITER/ADMIN gated for writes)
src/main/resources/
  db/migration/    # V1__init.sql, V2__... — never edit applied migrations
  application.yml
  application-api.yml    # flyway enabled, swagger enabled, UserDetailsService excluded
  application-worker.yml # no web server, flyway disabled, swagger disabled
```

---

## 6. Configuration & local development

- **No secrets in committed files.** Use `${ENV_VAR}` placeholders. Commit `.env.example`.
- **Datasource (dev):** `localhost:5432` — SSH tunnel to VPS or local Docker Postgres.
- **CI/CD:** GitHub Actions. test → build-push (GHCR) → deploy (SSH + docker compose).
- **Compose files:** `docker-compose.yml` (local dev) and `docker-compose.prod.yml` (VPS:
  postgres, api, worker, caddy, mosquitto, backoffice, landing). Both in-repo; VPS file is
  never manually maintained — deploy copies it.
- **`.gitignore` must include:** `target/`, `.env`, `*.log`, `.idea/`, `*.iml`

---

## 7. MONEY-SAFETY INVARIANTS (read twice — applies to online flows too)

1. **No mutable balance column.** Balance = `SUM(CREDIT) − SUM(DEBIT)` over ledger entries.
2. **Every money movement is balanced double-entry** in one DB transaction.
3. **Money is `BIGINT` (whole Rupiah).** Never `float`/`double`/`Float`/`Double`.
4. **Idempotency at DB level.**
   - Offline: `UNIQUE(sender_device_id, counter)` rejects replays.
   - **Online (new): `UNIQUE(idempotency_key)` on every online-money-movement table.** The
     device generates the key (see §14.4); the database, not application logic, is the final
     guard against duplicate submission.
5. **The API never posts to the ledger from the offline sync endpoint.** Only the worker
   settles offline transactions. **This does NOT apply to the new online endpoints** — those
   post synchronously in the same request (§3, "Online flows"). Do not conflate the two.
6. **Pouch outflows ≤ certificate.** Violation → flag, never post. (Offline pouch only —
   online transfers have no pouch and no certificate.)
7. **All `@Scheduled` jobs have ShedLock.**
8. **MQTT carries no financial authority.** Treat all MQTT input as untrusted hints.
9. **Never log secrets:** no PINs, private keys, signatures, tokens, passwords, idempotency
   keys tied to real transactions beyond what's needed for debugging.
10. **Schema only via Flyway.** Never edit an applied migration — add a new one.
11. **Failed/suspicious work is flagged, never silently dropped.**
12. **Every configurable money limit is a named config property, never a hardcoded literal.**
    (e.g. `pouch.max-amount-idr`, `transfer.online.max-amount-idr` — see §14.5.) A missing
    required property must fail startup, not silently default to an unsafe value.

---

## 8. MQTT topic contract

- `wallet/{deviceId}/status` — device → broker, retained, QoS 1. Last-Will = `offline`.
- `wallet/{deviceId}/sync-result` — worker → device, QoS 1. (Offline sync only.)
- `wallet/{deviceId}/cert-refresh` — worker → device, QoS 1.
- **New (optional, §14):** `wallet/{deviceId}/payment-received` — published by the API
  (not the worker) immediately after an `ONLINE_TRANSFER` or `QR_PAYMENT_ONLINE` credits a
  user's online balance, so the receiving device can refresh its balance display without
  polling. QoS 1, non-retained. This is a notification only — never trust it as proof of
  settlement; the ledger is authoritative.
- **ACL:** each device may only pub/sub under `wallet/{itsOwnDeviceId}/#`.
- **Transport:** TLS port 8883 only. Never plain 1883.
- Full topic contract: see `docs/MQTT_CONTRACT.md` (update it when the new topic ships).

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

**Deployed stack** (VPS): `dompet-postgres`, `dompet-api`, `dompet-worker`, `dompet-caddy`
(api./mqtt./backoffice./dompetgaruda.com/www.), `dompet-mosquitto`, `dompet-backoffice`,
`dompet-landing`.

---

## 10. Testing expectations

- Every ledger operation has a test asserting entries balance (credits = debits) — **including
  the new synchronous online postings**, not just offline settlement.
- **Read-only endpoints** must assert **zero rows written** to `ledger_entries`/`ledger_transactions`.
- Offline sync tests: happy path, replayed batch, over-pouch-limit, malformed batch, out-of-order counter.
- **New — online transfer tests:** happy path, insufficient balance, self-transfer rejected,
  duplicate idempotency key produces no duplicate posting, amount exceeds configured max.
- **New — Bayar QR Online tests:** happy path, expired request, already-paid request (nonce
  reuse), insufficient balance, duplicate idempotency key.
- Auth tests: correct/wrong credentials, brute-force limit, invalid/expired/missing JWT.
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
- Don't reference admin JWT/auth config in any bean without `@Profile("api")`.
- Don't expose port 8080 on the VPS host — `expose:` only, Caddy proxies externally.
- Don't include Claude as contributor — always use the human developer's GitHub profile.
- Don't reintroduce `ADMIN_API_TOKEN`.
- Don't build a public signup endpoint.
- **Don't call the QR payment feature "QRIS" anywhere — code, comments, docs, API paths, or
  UI copy. It is "Bayar QR". This is a legal/naming distinction, not a style preference (§1).**
- **Don't force the new online endpoints (Transfer Online, Bayar QR Online) into the
  sync_inbox/worker settlement pattern.** They are synchronous by design (§3).
- **Don't hardcode the online transfer limit or any new money limit.** Every limit is a
  configurable property (§7 invariant 12, §14.5).
- **Don't implement real QRIS/bank/PJP integration.** Explicitly out of scope (see PRD NG9).

---

## 13. Documentation deliverables (required per PR — not optional)

Every PR that introduces or changes at least one endpoint must ship these three artifacts.

### 13a. README.md — kept current, never appended

**Before opening any PR, run this and verify each heading appears exactly once:**
```bash
grep -n "^## " README.md
```
Never append a new `## Milestones` or `## API reference` section — update in place.

### 13b. Example API calls — `docs/api-examples/`

One numbered shell script per endpoint. `curl` command + expected response as comment.

### 13c. Swagger annotations

Every endpoint controller: `@Tag`, `@Operation`, `@ApiResponse` per status code. Every
request/response DTO: `@Schema` per field. Swagger enabled on `api` profile only.

---

## 14. NEW SCOPE — Online Transactions & Bayar QR (locked in, approved RAB)

This section defines the three new features approved in the RAB/Proposal dated August 2026.
Read this fully before starting any related PR. Build order: §14.1 → §14.2 → §14.3 → §14.6.

### 14.1 Transfer Online Antar Pengguna

Direct server-mediated transfer between two users' online balances. No BLE, no pouch,
no certificate — purely a ledger posting made synchronously by the API.

**Endpoint:** `POST /device/transfer`
```json
Request:
{
  "receiverUserId": "uuid",
  "amount": 50000
}
Headers:
  Authorization: Bearer <device token>
  Idempotency-Key: <UUID, generated by the device>

Success (200):
{
  "transactionId": 123,
  "senderNewBalance": 450000
}
```

**Validation, in order:**
1. Device token valid → else 401.
2. `Idempotency-Key` header present → else 400.
3. `receiverUserId` exists → else 404.
4. `receiverUserId` != sender's own `userId` → else **400 "Cannot transfer to yourself"** (locked decision — always reject self-transfer).
5. `amount` > 0 and `amount` <= `transfer.online.max-amount-idr` (see §14.5) → else 400.
6. Sender's online balance >= `amount` (derived from ledger, not cached) → else 422.
7. If `Idempotency-Key` was already processed for this device: return the **original** response with the same status code, do not reprocess or double-post (see §14.4).

**Posting:** One DB transaction — `ONLINE_TRANSFER`: DEBIT sender.ONLINE, CREDIT receiver.ONLINE.

**Notification:** After commit, publish `wallet/{receiverDeviceId}/payment-received` if the
receiver has a registered device (best-effort, never blocks the response).

---

### 14.2 Bayar QR Online

QR-assisted payment between two users while both are online. No Bluetooth. The QR is a
convenience for entering payment details — the actual transfer is a server-mediated ledger
posting, same integrity model as §14.1 but initiated via a two-step request/pay flow.

**Step 1 — Receiver creates a payment request:**
```
POST /device/payment-request
Body: { "amount": 75000 }
Headers: Authorization: Bearer <device token>

Success (201):
{
  "requestId": "uuid",
  "amount": 75000,
  "nonce": "base64-random",
  "expiresAt": "2026-08-10T10:15:00Z",
  "qrPayload": "<string the device encodes into a QR image>"
}
```

`qrPayload` format: `{requestId}|{receiverUserId}|{amount}|{nonce}` — kept simple since this
QR only carries a request pointer, not a signed transaction (contrast with §14.3, which does
carry more).

**Step 2 — Payer scans and pays:**
```
POST /device/payment-request/{requestId}/pay
Headers:
  Authorization: Bearer <device token>
  Idempotency-Key: <UUID, generated by the device>

Success (200):
{
  "transactionId": 456,
  "payerNewBalance": 375000
}
```

**Validation, in order:**
1. Device token valid → else 401.
2. `Idempotency-Key` present → else 400.
3. `requestId` exists → else 404.
4. Request status is `PENDING` (not already `PAID` or `EXPIRED`) → else **409** (nonce reuse
   protection — a request can only be paid once).
5. `now() < expiresAt` → else mark `EXPIRED` and return **410 Gone**.
6. Payer != receiver (cannot pay your own request) → else 400.
7. Payer's online balance >= request amount → else 422.
8. Idempotency-Key replay check, same as §14.1 step 7.

**Posting:** One DB transaction — `QR_PAYMENT_ONLINE`: DEBIT payer.ONLINE, CREDIT
receiver.ONLINE. Mark the payment_requests row `PAID`, set `paidAt`.

**Expiry:** payment requests default to a short TTL (**10 minutes** — configurable, see §14.5).
A `@Scheduled` + ShedLock job (`payment-request-expiry`) sweeps `PENDING` requests past
`expiresAt` and marks them `EXPIRED` every minute, independent of the pay endpoint's own
expiry check (belt-and-suspenders — the pay endpoint must never trust that the sweep already ran).

---

### 14.3 Bayar QR Offline (backend portion only)

**This reuses the existing offline BLE transfer flow (§3, "offline sync flow") almost
entirely.** The QR here only replaces manual amount/receiver entry on the sender's device —
it is scanned locally by the payer's device firmware, which then initiates the same BLE
signing exchange as a normal offline Transfer. **No new backend endpoint is required for the
transfer itself.**

Backend work is limited to:

1. **QR payload specification for the firmware team** (a doc, not code) — defines the
   offline QR content: `{receiverDeviceId}|{amount}|{nonce}|{expiresAt}`. Keep the payload
   small; QR codes have limited capacity, and this one must be scannable at typical camera
   resolutions on constrained hardware.
2. **Add an `origin` field to `offline_transactions`** — `BLE` (default, existing flow) or
   `QR` (new) — via Flyway migration. Purely informational; does not change settlement logic,
   signature verification, or counter checks. Populate it from a field the device includes in
   the signed batch payload if present, defaulting to `BLE` if absent.
3. **No change to the settlement service's verification logic.** A QR-originated offline
   transaction is verified identically to a BLE-originated one — same signatures, same
   counter rules, same pouch-limit checks. The QR is firmware-side UX only.

Do not build a payment-request table or expiry job for this flow — that machinery is only
needed for §14.2 (online), where there's no live BLE handshake to anchor trust to. Offline QR
trust comes from the existing Ed25519 signature exchange over BLE, same as it always has.

---

### 14.4 Idempotency (applies to §14.1 and §14.2 — cross-cutting requirement)

**The device generates the idempotency key**, not the server. Locked decision, rationale:
the device is the only party that knows whether it's retrying a request it already sent
(e.g. after a timeout with an ambiguous response) versus sending a genuinely new one.

- Key is a UUID (v4), sent as the `Idempotency-Key` HTTP header.
- Store it in a dedicated column with a **`UNIQUE` constraint** on every table that records
  an online money movement (`online_transfers`, `payment_requests` payment records, or a
  shared `idempotency_keys` table referencing the transaction — pick ONE consistent approach
  across both features, do not implement it differently per endpoint).
- On a duplicate key for the same device: **do not reprocess.** Look up the original result
  and return it with the original status code. The client-visible behavior of retrying a
  request must be indistinguishable from the request having succeeded once.
- A missing `Idempotency-Key` header is a **400**, not a silently-accepted request — this is
  a payment system; there is no safe default for "the client didn't say whether this is a retry."

---

### 14.5 Configuration — new required properties

All limits are configurable, per invariant 12. Add to `application.yml` with no hardcoded
fallback in code:

```yaml
transfer:
  online:
    max-amount-idr: ${TRANSFER_ONLINE_MAX_AMOUNT_IDR:10000000}

qr-payment:
  request-ttl-minutes: ${QR_PAYMENT_REQUEST_TTL_MINUTES:10}
```

**Locked value:** `transfer.online.max-amount-idr` = **Rp 10,000,000** for now. This is a
config value, not a constant — changing it later requires only an environment variable
update on the VPS, no code deploy. Document this in the PR that introduces it.

---

### 14.6 What this new scope explicitly does NOT include

See PRD §NG9 for the full non-goals list. In short: no real QRIS/bank/PJP integration, no
mobile app, no hardware procurement. Bayar QR Offline firmware (camera, QR display, scanning)
is not part of this backend work — see §14.3.