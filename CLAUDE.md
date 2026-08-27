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

The product also supports **online, server-mediated transactions** (Phase 2, delivered and
verified in production — see §14): direct online transfer between users, and "Bayar QR" — a
QR-code-based payment flow, both online and offline. **"Bayar QR" is NOT the national QRIS
standard.** There is no integration with Bank Indonesia, a bank, or a licensed payment
service provider. The QR code is generated and scanned entirely between Dompet Garuda
devices, and the transaction settles inside this system's own ledger. Never call it "QRIS"
in code, docs, or UI copy — always "Bayar QR".

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
- **Tests:** JUnit 5 + Testcontainers (real Postgres, and as of §15, real Mosquitto) for anything touching money or MQTT auth.
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
The same rule now also applies to the new `MqttAdminClient` (§15) — it is `@Profile("api")`
only, fully separate from the worker's existing Paho publisher bean.

### The offline sync flow (transactional inbox pattern — unchanged, still authoritative for offline)

1. Device uploads signed batch → API writes to `sync_inbox`, returns `202`. No ledger writes.
2. Worker polls `sync_inbox` with `SELECT ... FOR UPDATE SKIP LOCKED`.
3. Worker validates, posts ledger entries, publishes MQTT result.

### Online flows (Phase 2, delivered) do NOT use the inbox pattern

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
| `ONLINE_TRANSFER` | sender.ONLINE | receiver.ONLINE | API (synchronous) — **delivered** |
| `QR_PAYMENT_ONLINE` | payer.ONLINE | payee.ONLINE | API (synchronous) — **delivered** |

*(Balance enquiry is a read — not in this table. It moves no money.)*

---

## 4. Authentication (prototype-grade — NG1)

- **Admin/writer auth:** real user accounts in `admin_users` table (username + BCrypt password
  hash + role: `ADMIN` or `WRITER`). JWT-based, 24h expiry, `@Profile("api")` only.
- **Device registration:** admin-initiated. Server returns device token once, stores only hash.
- **Device auth:** device presents token as Bearer on device endpoints. Same token is used for
  offline endpoints and the online endpoints (§14) — no separate credential for online.
  As of §15, the **same device token is also reused as the device's MQTT password** — no
  new secret is minted for MQTT.
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
  transfer/        # DELIVERED — online transfer between users (§14.1). Introduced the
                   # idempotency_keys table, reused by qrpayment/.
  qrpayment/       # DELIVERED — Bayar QR online (§14.2, payment_requests table +
                   # PaymentRequestExpiryJob) and Bayar QR offline backend support (§14.3,
                   # origin column on offline_transactions).
  sync/            # api: ingest controller → sync_inbox / worker: inbox poller + settlement
  reconciliation/  # worker: pouch-vs-ledger reconciliation job (PouchReconciliationJob)
  mqtt/            # Paho client (worker publisher), topic publishers/subscribers.
                   # MqttAdminClient (§15, @Profile("api")) lives here too but is a
                   # SEPARATE bean/connection from the worker's publisher — never merge them.
  admin/           # admin-only actions: resolve flags, etc.
  article/         # article CRUD, public read endpoints (WRITER/ADMIN gated for writes)
src/main/resources/
  db/migration/    # V1__init.sql ... V7__offline_txn_origin.sql (latest as of this
                   # revision) — never edit applied migrations
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
- **Recurring failure mode — every new required env var must be added to the VPS `.env`
  manually before the first deploy that needs it, or the API container will crash-loop.**
  This has happened repeatedly (`ADMIN_JWT_SECRET`, `SERVER_SIGNING_KEY`,
  `TRANSFER_ONLINE_MAX_AMOUNT_IDR`, `QR_PAYMENT_REQUEST_TTL_MINUTES`, and again for
  `MQTT_API_ADMIN_USERNAME`/`MQTT_API_ADMIN_PASSWORD` in §15). Any PR introducing a new
  required property must explicitly call this out in its description.

---

## 7. MONEY-SAFETY INVARIANTS (read twice — applies to online flows too)

1. **No mutable balance column.** Balance = `SUM(CREDIT) − SUM(DEBIT)` over ledger entries.
2. **Every money movement is balanced double-entry** in one DB transaction.
3. **Money is `BIGINT` (whole Rupiah).** Never `float`/`double`/`Float`/`Double`.
4. **Idempotency at DB level.**
   - Offline: `UNIQUE(sender_device_id, counter)` rejects replays.
   - **Online: `UNIQUE(idempotency_key)` in the `idempotency_keys` table**, shared by
     `/device/transfer` and `/device/payment-request/{id}/pay`, keyed by
     `(device_id, idempotency_key, endpoint)`. The device generates the key (§14.4); the
     database, not application logic, is the final guard against duplicate submission.
     **Verified in production**: replaying the same key twice produces byte-identical
     responses and exactly one ledger posting, confirmed by direct row-count query.
5. **The API never posts to the ledger from the offline sync endpoint.** Only the worker
   settles offline transactions. **This does NOT apply to the online endpoints** — those
   post synchronously in the same request (§3, "Online flows"). Do not conflate the two.
6. **Pouch outflows ≤ certificate.** Violation → flag, never post. (Offline pouch only —
   online transfers have no pouch and no certificate.)
7. **All `@Scheduled` jobs have ShedLock.** Confirmed for `sync-inbox-poller`,
   `reconciliation-job` (now named `PouchReconciliationJob`, runs hourly), and
   `payment-request-expiry` (`PaymentRequestExpiryJob`, runs every minute).
8. **MQTT carries no financial authority.** Treat all MQTT input as untrusted hints.
9. **Never log secrets:** no PINs, private keys, signatures, tokens, passwords, idempotency
   keys tied to real transactions beyond what's needed for debugging. This now explicitly
   includes MQTT passwords (§15) — never log a device token or MQTT admin password.
10. **Schema only via Flyway.** Never edit an applied migration — add a new one.
11. **Failed/suspicious work is flagged, never silently dropped.**
12. **Every configurable money limit is a named config property, never a hardcoded literal.**
    (e.g. `pouch.max-amount-idr`, `transfer.online.max-amount-idr` — see §14.5.) A missing
    required property must fail startup, not silently default to an unsafe value.

---

## 8. MQTT topic contract

- `wallet/{deviceId}/status` — device → broker, retained, QoS 1. Last-Will = `offline`.
- `wallet/{deviceId}/sync-result` — worker → device, QoS 1. (Offline sync only. Notifies
  the **uploading** device about its own batch outcome — see §16 for the known gap where
  the *receiving* device in an offline transfer is not separately notified.)
- `wallet/{deviceId}/cert-refresh` — worker → device, QoS 1.
- `wallet/{deviceId}/payment-received` — published by the API (not the worker) immediately
  after an `ONLINE_TRANSFER` or `QR_PAYMENT_ONLINE` credits a user's online balance, so the
  receiving device can refresh its balance display without polling. QoS 1, non-retained.
  Notification only — never trust it as proof of settlement; the ledger is authoritative.
- **ACL:** each device may only pub/sub under `wallet/{itsOwnDeviceId}/#`. As of §15, this
  is enforced by the Mosquitto **Dynamic Security plugin**, not a static `acl` file — see
  §15 for the full authorization model and current production status.
- **Transport:** TLS port 8883 only. Never plain 1883 (1883 is bound to `127.0.0.1` only,
  used solely for local `mosquitto_ctrl` administration inside the container/VPS).
- Full topic contract: see `docs/MQTT_CONTRACT.md`.

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

**Mosquitto-specific administration** (see §15 for full detail):
```bash
# Always use exec (a running container), never `run` (a new, isolated container)
docker compose -f docker-compose.prod.yml exec mosquitto sh -c "mosquitto_ctrl -h 127.0.0.1 -p 1883 -u admin -P '<admin password>' dynsec <command>"
```

---

## 10. Testing expectations

- Every ledger operation has a test asserting entries balance (credits = debits) — including
  the synchronous online postings, not just offline settlement.
- **Read-only endpoints** must assert **zero rows written** to `ledger_entries`/`ledger_transactions`.
- Offline sync tests: happy path, replayed batch, over-pouch-limit, malformed batch, out-of-order counter.
- **Online transfer tests (delivered):** happy path, insufficient balance, self-transfer rejected,
  duplicate idempotency key produces no duplicate posting, amount exceeds configured max —
  all passing in CI, and additionally hand-verified against the live production API.
- **Bayar QR Online tests (delivered):** happy path, expired request (410), already-paid
  request (409), unknown request (404), insufficient balance, duplicate idempotency key on
  `/pay` — all passing in CI and hand-verified in production.
- **Bayar QR Offline tests (delivered):** a batch transaction with `origin: "QR"` settles
  identically to one without it (same ledger postings), a batch with no `origin` field
  defaults to `BLE` (regression check), and an over-limit `origin: "QR"` transaction is
  flagged with the identical reason/logic as an over-limit `BLE` one — confirming `origin`
  never gates a verification branch.
- **MQTT provisioning tests (§15, in progress):** use a real `eclipse-mosquitto:2`
  Testcontainer configured with the dynamic-security plugin (folder-mounted state file,
  correct ownership — see §15 for the exact gotchas) — not a mock. Assert: successful
  registration produces an MQTT client that can actually connect with the returned device
  token; a Mosquitto outage during registration causes the whole registration to roll back
  (zero rows in `devices`); suspend/reinstate actually blocks/restores the MQTT connection.
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
- **Don't force the online endpoints (Transfer Online, Bayar QR Online) into the
  sync_inbox/worker settlement pattern.** They are synchronous by design (§3).
- **Don't hardcode any money limit.** Every limit is a configurable property (§7 invariant 12).
- **Don't implement real QRIS/bank/PJP integration.** Explicitly out of scope (see PRD NG9).
- **Don't re-run the manual Mosquitto Dynamic Security migration through code (§15).** The
  broker-side infrastructure migration is already done manually on the VPS. New PRs only add
  application code that *uses* the existing admin/worker/device-role accounts — they do not
  reconfigure Mosquitto itself.
- **Don't touch `docker-compose.prod.yml`'s Mosquitto volume mounts without re-reading §15
  first** — the folder-vs-file bind-mount distinction there is load-bearing, not stylistic.

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
As of Phase 2: scripts 19–21 cover `/device/transfer`, `/device/payment-request`, and
`/device/payment-request/{id}/pay` respectively, each demonstrating idempotency replay.

### 13c. Swagger annotations

Every endpoint controller: `@Tag`, `@Operation`, `@ApiResponse` per status code. Every
request/response DTO: `@Schema` per field. Swagger enabled on `api` profile only.

---

## 14. Phase 2 scope — Online Transactions & Bayar QR (DELIVERED, verified in production)

All three features below have been built, merged, deployed, and manually verified against
the live production API (`https://api.dompetgaruda.com`) — not just passing CI. See §10 for
the specific test evidence and the PRD §8 success criteria for the exact verification steps
performed.

### 14.1 Transfer Online Antar Pengguna — DELIVERED

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
3. If `Idempotency-Key` was already processed for this device: return the **original**
   response with the same status code, do not reprocess or double-post (§14.4). **Verified**:
   replaying an identical request with the same key returns an identical response and adds
   zero additional `ledger_transactions` rows.
4. `receiverUserId` exists → else 404.
5. `receiverUserId` != sender's own `userId` → else **400 "Cannot transfer to yourself"**.
   **Verified in production.**
6. `amount` > 0 and `amount` <= `transfer.online.max-amount-idr` → else 400. **Verified**
   (an amount of `10000001` against the default `10000000` limit was rejected).
7. Sender's online balance >= `amount` (derived from ledger, not cached) → else 422.

**Posting:** One DB transaction — `ONLINE_TRANSFER`: DEBIT sender.ONLINE, CREDIT receiver.ONLINE.

**Notification:** After commit, publish `wallet/{receiverDeviceId}/payment-received` if the
receiver has a registered device (best-effort, never blocks the response).

---

### 14.2 Bayar QR Online — DELIVERED

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
  "qrPayload": "<string the device encodes into a QR image locally>"
}
```

`qrPayload` format: `{requestId}|{receiverUserId}|{amount}|{nonce}`. The backend **never**
generates a QR image — only this text payload. The device renders the actual QR code
locally using a standard QR-encoding library on the firmware side (this was a deliberate
architecture decision — see §16 for the accompanying discussion with the client about why
image generation belongs on-device, not server-side).

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

**Validation, in order (all verified in production):**
1. Device token valid → else 401.
2. `Idempotency-Key` present → else 400.
3. Idempotency replay check (same `idempotency_keys` table as §14.1, keyed additionally by
   endpoint) → if already processed, return original response, no reprocessing.
4. `requestId` exists → else 404. **Verified** (unknown UUID → 404).
5. Request status is `PENDING` → else **409**. **Verified** (paying an already-`PAID`
   request with a fresh idempotency key correctly returns 409, not a second posting).
6. `now() < expiresAt` → else mark `EXPIRED`, return **410 Gone**.
7. Payer != receiver → else 400.
8. Payer's online balance >= request amount → else 422.

**Posting:** One DB transaction — `QR_PAYMENT_ONLINE`: DEBIT payer.ONLINE, CREDIT
receiver.ONLINE. Marks the `payment_requests` row `PAID`, sets `paidAt`.

**Expiry:** TTL configurable via `qr-payment.request-ttl-minutes` (default 10 minutes). A
`@Scheduled` + ShedLock job (`PaymentRequestExpiryJob`, confirmed running every minute in
production logs) sweeps `PENDING` requests past `expiresAt` independent of the pay
endpoint's own real-time expiry check.

**Amount integrity note:** `/pay` reads the amount from the stored `payment_requests` row,
never from the client's request body or from re-parsing the QR payload — the QR's displayed
amount is UX only, not the source of truth. This means a tampered QR cannot change what is
actually charged.

---

### 14.3 Bayar QR Offline (backend portion) — DELIVERED

This reuses the existing offline BLE transfer flow (§3, "offline sync flow") almost
entirely. The QR only replaces manual amount/receiver entry on the sender's device — it is
scanned locally by the payer's device firmware, which then initiates the same BLE signing
exchange as a normal offline Transfer. No new backend endpoint was required for the transfer
itself.

**What was actually delivered:**
1. `V7__offline_txn_origin.sql` — `offline_transactions.origin VARCHAR(10) NOT NULL DEFAULT
   'BLE' CHECK (origin IN ('BLE','QR'))`.
2. `SyncOfflineTxnRequest` gained an optional `origin` field in the signed batch payload
   (device may set `"QR"`; absent → defaults to `"BLE"`).
3. `docs/QR_OFFLINE_PAYLOAD_SPEC.md` — the QR payload spec for the firmware team:
   `{receiverDeviceId}|{amount}|{nonce}|{expiresAt}`, recommended error-correction level M,
   estimated QR version 3–5. **Confirmed ready to hand off to firmware.**
4. **Confirmed by direct code review**: `origin` appears in exactly one place in
   `SyncSettlementService` — the final INSERT statement, after all verification (counter
   check, both signature checks, outflow-cap check) has already passed. It is never read by
   any trust/verification branch.

No `payment_requests`-style table exists for this flow, by design — that pattern is only
needed where there's no live BLE handshake to anchor trust to (§14.2). Offline QR trust
comes entirely from the existing Ed25519 signature exchange over BLE.

---

### 14.4 Idempotency — DELIVERED, shared pattern

**The device generates the idempotency key**, not the server (locked decision, R8).

- Key is a UUID (v4), sent as the `Idempotency-Key` HTTP header.
- Storage: a dedicated `idempotency_keys` table — `(device_id, idempotency_key, endpoint)`
  UNIQUE, plus `response_status` and `response_body` (JSONB) columns storing the original
  response verbatim for replay. This exact table is shared, unmodified, by both
  `/device/transfer` and `/device/payment-request/{id}/pay` — confirmed in both PR
  descriptions as the identical mechanism, not two separate implementations.
- On a duplicate key for the same device+endpoint: the stored response is replayed exactly,
  with **zero** re-validation and zero additional ledger writes. **This has been directly
  verified against production** by calling each endpoint twice with the same key and
  confirming (a) byte-identical responses and (b) an unchanged `ledger_transactions` row
  count via direct database query.
- A missing `Idempotency-Key` header is a 400.

---

### 14.5 Configuration — delivered, currently active values

```yaml
transfer:
  online:
    max-amount-idr: ${TRANSFER_ONLINE_MAX_AMOUNT_IDR:10000000}   # Rp 10,000,000

qr-payment:
  request-ttl-minutes: ${QR_PAYMENT_REQUEST_TTL_MINUTES:10}       # 10 minutes
```

Both are live on the VPS `.env`. No hardcoded fallback exists in the Java code for
`transfer.online.max-amount-idr` (a money-safety limit); `qr-payment.request-ttl-minutes`
has a YAML-level default since an expiry window is a UX parameter, not a money-safety limit.

---

### 14.6 What this scope explicitly does NOT include

See PRD §NG9/§NG10. In short: no real QRIS/bank/PJP integration, no mobile app, no hardware
procurement. Bayar QR Offline firmware (camera, QR rendering, scanning) is a firmware-team
responsibility, not part of this backend's costed scope.

---

## 15. MQTT Per-Device Provisioning (Dynamic Security) — infrastructure done, backend PR pending

Previously, MQTT credentials only existed for the worker (`dompet-worker`). No device ever
had its own credentials — this gap surfaced while walking through the offline transfer
scenario end-to-end. This section is the current, ground-truth state of the fix as of this
revision — broker infrastructure is migrated and tested; the application-layer PR
(`MqttAdminClient` + registration/status-endpoint integration) has not yet been written.

### Infrastructure — DONE manually on the VPS, do not repeat this migration via code

- Mosquitto is migrated from static `password_file` + `acl_file` to the **Dynamic Security
  plugin** built into Mosquitto 2.x.
- Plugin binary path on the `eclipse-mosquitto:2` image (Alpine-based):
  `/usr/lib/mosquitto_dynamic_security.so` — **not** the Debian-style path.
- State file: `/mosquitto/dynsec/dynamic-security.json`. **Must be a folder bind-mount**,
  not a single-file bind-mount — a single-file mount becomes a kernel-level mount point
  that Mosquitto's write-temp-then-rename save routine cannot replace (`Resource busy`).
- The `dynsec` folder **must be owned by `mosquitto:mosquitto`** (the container's internal
  UID), not the VPS host user — Mosquitto drops root privilege and runs as user `mosquitto`
  immediately after start, even with `user: root` on the container.
- Relevant `docker-compose.prod.yml` volumes for the `mosquitto` service:
  ```yaml
  volumes:
    - ./mosquitto/config/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro
    - ./mosquitto/config/passwd:/mosquitto/config/passwd:ro
    - ./mosquitto/config/acl:/mosquitto/config/acl:ro
    - ./mosquitto/start.sh:/start.sh:ro
    - ./mosquitto/dynsec:/mosquitto/dynsec
    - mosquitto_data:/mosquitto/data
    - mosquitto_certs:/mosquitto/certs
    - caddy_data:/caddy_data:ro
  ```
  The old `passwd`/`acl` files are left in place but unused once the plugin is active —
  it fully replaces file-based authorization, it does not layer on top of it.
- `mosquitto.conf` additions:
  ```
  plugin /usr/lib/mosquitto_dynamic_security.so
  plugin_opt_config_file /mosquitto/dynsec/dynamic-security.json
  ```
- **Administrative commands must use `docker compose exec`, never `docker compose run`.**
  `run` creates a brand-new, network-isolated container — `127.0.0.1:1883` inside it is
  *not* the running broker.

### Accounts that exist today (August 2026)

| Username | Role | Purpose |
|---|---|---|
| `admin` | `admin` | Administers the dynamic-security plugin itself |
| `dompet-worker` | `worker-role` (`publishClientSend` on `wallet/#`) | Worker's existing publisher (`sync-result`, `cert-refresh`, `payment-received`) |
| `dompet-api-admin` | `admin` | **New** — reserved for the API's `MqttAdminClient` (not yet used by any code) |

The `device-role` role (`publishClientSend` + `subscribePattern` on the pattern
`wallet/%u/#`) has also been created, but **no device is attached to it yet** — that is the
job of the pending backend PR below.

⚠️ **Known tech debt, intentional for now:** all accounts currently share one weak
password, chosen deliberately to unblock end-to-end verification quickly. This must be
rotated to distinct, strong, generated passwords before any real production load —
recorded here so it isn't forgotten, not as a long-term recommendation.

### Device credentials — reuse the device token, mint no new secret

- **MQTT username = `deviceId`** (UUID).
- **MQTT password = the same device token** already generated at `POST /admin/devices`.
  No new secret surface to manage or leak.
- Attach every device to the single shared `device-role` — never create a role per device.

### `MqttAdminClient` — new bean, `@Profile("api")` only, NOT YET IMPLEMENTED

```
MqttAdminClient  (@Profile("api") only)
  - Connects as dompet-api-admin (credentials: MQTT_API_ADMIN_USERNAME /
    MQTT_API_ADMIN_PASSWORD, already present in the VPS .env)
  - provisionDevice(deviceId, deviceToken)
      → createClient (username=deviceId, password=deviceToken)
      → addClientRole (username=deviceId, roleName="device-role")
  - revokeDevice(deviceId)     → disableClient
  - reinstateDevice(deviceId)  → enableClient
```

Sends commands by publishing to `$CONTROL/dynamic-security/v1` and awaiting a response on
`$CONTROL/dynamic-security/v1/response` on a short-lived, single-purpose MQTT connection —
the same pattern `mosquitto_ctrl` itself uses internally.

### Provisioning is REQUIRED, not best-effort; revoke is best-effort

Unlike notification publishing (`sync-result` etc. — allowed to fail silently), **MQTT
provisioning is a mandatory part of device registration**. A silent failure here creates a
device that can *never* receive push notifications, permanently.

**Required flow inside `POST /admin/devices` (saga / compensating-action pattern):**
1. Insert the device row (inside the transaction).
2. Call `MqttAdminClient.provisionDevice()`. On failure: **roll back** the transaction
   (device is not persisted), return 503.
3. On success: commit.

`PATCH /admin/devices/{deviceId}/status` should call `revokeDevice()` on transition to
`SUSPENDED`/`LOCKED`, and `reinstateDevice()` on transition back to `ACTIVE` — but a failure
here must **not** block the status change itself (log a WARNING instead); an emergency
suspend of a lost/stolen device must never be blocked by an unrelated MQTT outage.

### Testing requirements for the pending PR

Use a real `eclipse-mosquitto:2` Testcontainer configured with the dynamic-security plugin
— replicating every infrastructure detail above (folder mount, correct ownership, `dynsec
init` bootstrap) — not a mock. At minimum: successful provisioning yields a client that can
actually connect with the device token; a broker outage during registration causes a full
rollback (assert zero rows in `devices`, not just a 503 response); suspend/reinstate
genuinely blocks/restores the MQTT connection; status-change endpoint still returns 200 and
updates the database even when Mosquitto is unreachable at that moment.

---

## 16. Known gaps surfaced during the offline-transfer scenario walkthrough (not yet resolved)

These were discovered while documenting the full offline BLE transfer flow end-to-end, and
are recorded here so they are not lost. None of them are bugs in delivered code — they are
design work that has not yet been done.

### 16.1 BLE certificate-exchange protocol is undefined

No GATT Service/Characteristic structure has been specified for exchanging the sender's
offline certificate and the transaction proposal/signatures between two devices over BLE.
A draft structure (three characteristics — `certificate-exchange`, `transaction-proposal`,
`signature-response`) has been proposed to the firmware team but is not yet final. See
`docs/SPESIFIKASI_BLE_DAN_MQTT_DEVICE.md` (Indonesian) for the full draft.

### 16.2 Server public key must be embedded in device firmware

For a receiving device to verify a sender's offline certificate at BLE-handshake time
(§3, offline sync flow), it needs the server's Ed25519 **public** key (derived from
`SERVER_SIGNING_KEY`) baked into firmware at flash time. **Consequence:** rotating
`SERVER_SIGNING_KEY` in the future requires an OTA firmware update campaign for every
device already in the field — this is not solvable server-side alone. Flagged as a
required input to any future key-rotation or incident-response plan, not an immediate
blocker.

### 16.3 BLE handshake is a liveness check, not a full identity verification

Because devices never exchange public keys with each other ahead of time (registration is
centralized at the server, not peer-to-peer), the BLE mutual-authentication step only
proves that the counterpart device currently holds *some* private key it claims to — real
identity verification happens later, server-side, at settlement time, when the worker
checks signatures against the centrally registered public key. This is consistent with the
existing FR3a principle (local checks are UX guards, not the security control) and is not
considered a vulnerability, but it must stay clearly understood by anyone working on the
firmware protocol.

### 16.4 The offline transfer's *receiving* device gets no MQTT push

`wallet/{deviceId}/sync-result` (§8) only notifies the **uploading** (sender) device about
its own batch's settlement outcome. The receiving device in that same transfer is not
separately notified — it only discovers the new balance the next time it calls
`GET /device/balance` itself. This is not a bug (settlement correctness does not depend on
it), but it is an inconsistency worth resolving relative to the `payment-received` pattern
already built for the online flows (§8) — not yet scoped as a PR.