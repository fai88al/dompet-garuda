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
verified in production): direct online transfer between users, and "Bayar QR" — a
QR-code-based payment flow, both online and offline. **"Bayar QR" is NOT the national QRIS
standard.** There is no integration with Bank Indonesia, a bank, or a licensed payment
service provider. Never call it "QRIS" in code, docs, or UI copy — always "Bayar QR".

**Stage: prototype.** Prefer simple, correct, auditable code over cleverness or premature scale.

---

## 1a. ⚠️ Device ID format change (August 2026) — read before touching anything device-related

> [!warning] Breaking change, decided with the hardware team
> `deviceId` is **no longer a UUID v4**. It is now a **plain string**, sourced from the
> firmware/hardware team's own identifier scheme (most likely derived from the ESP32's
> factory MAC address, but confirm the exact source format before writing validation
> regex or migration DDL with hard length assumptions).

### Required format constraints (locked, regardless of exact source format)

`deviceId` **must never contain `/` or `|`**. Both characters already have structural
meaning elsewhere in the system and a device ID containing either will silently corrupt
unrelated logic:

- `/` is a level separator in MQTT topics (`wallet/{deviceId}/#` — see §8, §15). A `/`
  inside `deviceId` creates unintended topic sub-levels.
- `|` is the field delimiter in the offline signature message format (see §14, offline
  sync flow): `{offlineTxnId}|{senderDeviceId}|{receiverDeviceId}|{amount}|{counter}|
  {deviceTimestamp}`. A `|` inside `deviceId` breaks unambiguous parsing of that string.

**Validate this explicitly at the point of device registration** (`POST /admin/devices`)
— reject with 400 if either character is present. Do not rely on downstream code to
happen to handle it safely.

### What actually changes in the schema and code

- `devices.device_id` column type: `UUID` → `VARCHAR` (exact length TBD — see the open
  question below; use a generous bound like `VARCHAR(128)` unless the hardware team
  confirms a fixed length, e.g. 12 or 17 characters for a MAC-derived ID).
- Every foreign key referencing `devices.device_id` changes type to match:
  `offline_transactions`, `sync_inbox`, `offline_certificates`, ledger accounts tied to
  a device's pouch, `idempotency_keys` (device-scoped rows), and the MQTT
  `dynamic-security.json` `username` field (already a string in Mosquitto's model, so
  no change needed there — but stop assuming it's UUID-shaped when writing new code
  against it, e.g. in `MqttAdminClient`).
- **This requires a new Flyway migration** — do not attempt to alter the column type
  silently as a side effect of another PR. Write it as its own migration
  (`V(next)__device_id_to_string.sql`), and confirm with the human developer how
  existing rows (which currently hold UUID-formatted strings) should be handled —
  likely no data transformation is needed since a UUID string is *already* a valid
  string, but the column's `UUID` type constraint itself must be dropped.
- `receiverDeviceId` / `senderDeviceId` fields in API request/response bodies and
  Swagger schemas: change documented type from `"uuid"` to `"string"` everywhere they
  appear (Transfer Online, Bayar QR payloads, sync batch payloads, admin device
  endpoints).
- **`userId` and other UUID-typed identifiers are UNCHANGED** — this only affects
  `deviceId`. Do not conflate the two or apply this change more broadly than asked.

### Documents that also need this update (outside this repo's CLAUDE.md)

- `docs/QR_OFFLINE_PAYLOAD_SPEC.md` — payload format
  `{receiverDeviceId}|{amount}|{nonce}|{expiresAt}` currently documents
  `receiverDeviceId` as a UUID; update to "string, format defined by hardware team,
  never containing `/` or `|`."
- The BLE protocol draft (`SPESIFIKASI_BLE_DAN_MQTT_DEVICE.md` / vault note
  `MQTT Dynamic Security`) — any GATT payload size estimates that assumed a 36-character
  UUID string need rechecking once the real format is confirmed (a MAC-derived string is
  typically shorter, which is a net improvement for BLE packet size, not a regression).

### Open question — confirm with hardware team before finalizing the migration

What is the exact source and format? (e.g., raw 6-byte MAC as 12 hex characters with no
separator, `AA:BB:CC:DD:EE:FF` with colons, or something else entirely.) This determines
the exact column length bound and whether any additional character-set validation is
needed beyond the `/` and `|` prohibition above.

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
- **Tests:** JUnit 5 + Testcontainers (real Postgres, real Mosquitto for MQTT auth tests).
- **API docs:** Springdoc OpenAPI. Swagger UI at `/swagger-ui.html`, api profile only.
- **Password hashing:** Spring Security `BCryptPasswordEncoder`, cost factor 10.
- **JWT:** `io.jsonwebtoken` (jjwt) for admin/writer session tokens.

> **Package root:** `com.dompetgaruda.api`. Everything follows this value.

### Migration ownership

- **API service:** `spring.flyway.enabled=true`, `ddl-auto=validate`. Runs migrations on boot.
- **Worker service:** `spring.flyway.enabled=false`, `ddl-auto=validate`. Never migrates.

### Spring Security autoconfiguration (REQUIRED)

`UserDetailsServiceAutoConfiguration` must be **excluded** in the API profile. Add to
`application-api.yml`:

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
```

---

## 3. Architecture (decided — build to this, don't reinvent)

**One codebase, one Docker image, two runtime containers** distinguished only by Spring profile:

- **`api` profile** — REST endpoints enabled, all `@Scheduled` jobs disabled. Runs Flyway.
- **`worker` profile** — `spring.main.web-application-type=none`, scheduled jobs enabled, Flyway disabled.

### Profile isolation rule (learned in production — do not repeat this bug)

Any Spring bean that references admin config (`ADMIN_JWT_SECRET`, `MqttAdminClient`,
admin-specific services) **must be annotated `@Profile("api")`** so it never loads in the
worker profile. A missing annotation crashes the worker at startup with
`PlaceholderResolutionException`. This is a hard rule.

### The offline sync flow (transactional inbox pattern)

1. Device uploads signed batch → API writes to `sync_inbox`, returns `202`. No ledger writes.
2. Worker polls `sync_inbox` with `SELECT ... FOR UPDATE SKIP LOCKED`.
3. Worker validates, posts ledger entries, publishes MQTT result.

### Online flows do NOT use the inbox pattern

Online transfer and Bayar QR Online are synchronous, server-mediated operations. The API
validates and posts directly to the ledger in the same request.

### Ledger posting reference

| Type | Debit | Credit | Settled by |
|------|-------|--------|------------|
| `TOPUP` | SYSTEM | user.ONLINE | API (synchronous) |
| `POUCH_LOAD` | user.ONLINE | device.POUCH | API (synchronous) |
| `OFFLINE_TRANSFER` | sender.POUCH | receiver.ONLINE | Worker (async, at sync) |
| `POUCH_REFUND` | device.POUCH | user.ONLINE | Worker (async, at sync) |
| `ONLINE_TRANSFER` | sender.ONLINE | receiver.ONLINE | API (synchronous) |
| `QR_PAYMENT_ONLINE` | payer.ONLINE | payee.ONLINE | API (synchronous) |

---

## 4. Authentication (prototype-grade — NG1)

- **Admin/writer auth:** real accounts in `admin_users` (BCrypt + role). JWT, 24h expiry,
  `@Profile("api")` only.
- **Device registration:** admin-initiated. Server returns device token once, stores only
  hash. `deviceId` itself is now a **string, not a UUID** — see §1a.
- **Device auth:** device presents token as Bearer. Same token doubles as the device's
  MQTT password (§15) — no separate credential minted for MQTT.

### Admin/writer login

```
POST /admin/auth/login
Body:    { "username": "...", "password": "..." }
Success: 200 { "token": "<JWT>", "type": "Bearer", "username": "...", "role": "ADMIN"|"WRITER" }
Failure: 401 { "message": "Invalid username or password" }
```

Brute-force protection: 5 failed attempts / 5 min / IP → 429.

---

## 5. Module layout

```
src/main/java/com/dompetgaruda/api/
  common/          # entities, ledger posting, Ed25519 verification, DTOs
  config/          # ApiConfig, WorkerConfig (@Profile-gated), SecurityConfig, MqttConfig
  auth/            # AdminTokenFilter (@Profile("api"), JWT), DeviceTokenService,
                   # DeviceTokenVerifier, AdminLoginController, AdminUser entity/repository
  device/          # device registration, certificate issuance, device status admin endpoints.
                   # deviceId validation (reject "/" and "|") lives here — see §1a.
  wallet/          # balance enquiry (read), top-up, pouch provisioning
  ledger/          # LedgerPostingService — double-entry posting, balance derivation
  transfer/        # Online transfer between users. idempotency_keys table, shared with qrpayment/.
  qrpayment/       # Bayar QR online (payment_requests table) + Bayar QR offline backend support
                   # (origin column on offline_transactions).
  sync/            # api: ingest controller → sync_inbox / worker: inbox poller + settlement
  reconciliation/  # worker: pouch-vs-ledger reconciliation job (PouchReconciliationJob)
  mqtt/            # Paho client (worker publisher). MqttAdminClient (@Profile("api"), §15)
                   # is a SEPARATE bean/connection — never merge them.
  admin/           # admin-only actions: resolve flags, etc.
  article/         # article CRUD, public read endpoints (WRITER/ADMIN gated for writes)
src/main/resources/
  db/migration/    # V1__init.sql ... — includes the new device_id-to-string migration (§1a).
                   # Never edit an applied migration.
  application.yml
  application-api.yml
  application-worker.yml
```

---

## 6. Configuration & local development

- **No secrets in committed files.** Use `${ENV_VAR}` placeholders. Commit `.env.example`.
- **CI/CD:** GitHub Actions. test → build-push (GHCR) → deploy (SSH + docker compose).
- **`.gitignore` must include:** `target/`, `.env`, `*.log`, `.idea/`, `*.iml`
- **Recurring failure mode — every new required env var must be added to the VPS `.env`
  manually before the first deploy that needs it, or the API container will crash-loop.**
  This has happened repeatedly. Any PR introducing a new required property must
  explicitly call this out in its description.

---

## 7. MONEY-SAFETY INVARIANTS (read twice)

1. **No mutable balance column.** Balance = `SUM(CREDIT) − SUM(DEBIT)` over ledger entries.
2. **Every money movement is balanced double-entry** in one DB transaction.
3. **Money is `BIGINT` (whole Rupiah).** Never `float`/`double`/`Float`/`Double`.
4. **Idempotency at DB level.**
   - Offline: `UNIQUE(sender_device_id, counter)` — note `sender_device_id` is now a
     string column, not UUID; the uniqueness constraint itself is unaffected by the type
     change, just the underlying column type (§1a).
   - Online: `UNIQUE(idempotency_key)` in `idempotency_keys`, shared by
     `/device/transfer` and `/device/payment-request/{id}/pay`.
5. **The API never posts to the ledger from the offline sync endpoint.** Only the worker
   settles offline transactions. Online endpoints post synchronously — do not conflate.
6. **Pouch outflows ≤ certificate.** Violation → flag, never post.
7. **All `@Scheduled` jobs have ShedLock.**
8. **MQTT carries no financial authority.** Treat all MQTT input as untrusted hints.
9. **Never log secrets:** PINs, private keys, signatures, tokens, passwords, MQTT
   credentials.
10. **Schema only via Flyway.** Never edit an applied migration — add a new one.
11. **Failed/suspicious work is flagged, never silently dropped.**
12. **Every configurable money limit is a named config property**, never a hardcoded
    literal. A missing required property must fail startup.

---

## 8. MQTT topic contract

- `wallet/{deviceId}/status` — device → broker, retained, QoS 1. Last-Will = `offline`.
  **`deviceId` here is now a plain string (§1a) — the `/` prohibition in §1a exists
  specifically because this topic pattern would otherwise break.**
- `wallet/{deviceId}/sync-result` — worker → sending device, QoS 1.
- `wallet/{deviceId}/cert-refresh` — worker → device, QoS 1.
- `wallet/{deviceId}/payment-received` — API/worker → receiving device, QoS 1,
  non-retained. Notification only — never trust it as proof of settlement.
- **ACL:** each device may only pub/sub under `wallet/{itsOwnDeviceId}/#`, enforced by
  the Mosquitto Dynamic Security plugin (§15).
- **Transport:** TLS port 8883 only. Port 1883 is `127.0.0.1`-bound, local admin only.

---

## 9. Commands

```bash
./mvnw clean verify                                          # must pass before any PR
./mvnw spring-boot:run -Dspring-boot.run.profiles=api
./mvnw spring-boot:run -Dspring-boot.run.profiles=worker
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs api --tail=50
docker compose -f docker-compose.prod.yml exec mosquitto sh -c "mosquitto_ctrl -h 127.0.0.1 -p 1883 -u admin -P '<admin password>' dynsec <command>"
```

---

## 10. Testing expectations

- Every ledger operation asserts entries balance (credits = debits).
- Read-only endpoints assert **zero rows written**.
- Offline sync tests: happy path, replayed batch, over-pouch-limit, malformed batch,
  out-of-order counter.
- Online transfer / Bayar QR Online tests: happy path, insufficient balance, self-transfer,
  duplicate idempotency key, expired/already-paid request.
- Bayar QR Offline: `origin` never gates a verification branch (identical behavior for
  `BLE` and `QR`).
- **New — device ID format tests (§1a):** registration rejects a `deviceId` containing
  `/` or `|` with 400; a valid string-format `deviceId` (non-UUID-shaped) registers
  successfully and works correctly through the full offline BLE flow, MQTT provisioning,
  and every endpoint that takes `deviceId` as a path or body parameter — do not leave any
  code path that implicitly assumes UUID shape (e.g., regex validation somewhere that
  still checks for UUID format needs to be found and removed).
- MQTT provisioning: real Mosquitto Testcontainer, not a mock.
- Use Testcontainers (real Postgres) — do not mock the database for money logic.

---

## 11. Git workflow

- Repo lives in the **client's** GitHub org. Use your own account.
- **Commits authored by the human developer's GitHub account.**
- Never push directly to `main`. Never force-push a shared branch.
- Feature branch → PR against `main` → human reviews → merge.
- `./mvnw clean verify` must pass before opening a PR.

---

## 12. What NOT to do

- Don't scaffold a generic CRUD app. Don't add microservices/message brokers/service mesh.
- Don't use ORM-generated queries for money movements.
- Don't expand scope beyond the PRD — raise questions first.
- Don't turn balance enquiry into transaction history without confirming scope (see the
  Phase 3 proposal under discussion).
- Don't store or transmit money decisions over MQTT.
- Don't reference admin JWT/auth config in any bean without `@Profile("api")`.
- Don't reintroduce `ADMIN_API_TOKEN`. Don't build a public signup endpoint.
- **Don't call the QR payment feature "QRIS" anywhere.**
- **Don't force online endpoints into the sync_inbox/worker settlement pattern.**
- **Don't hardcode any money limit.**
- **Don't implement real QRIS/bank/PJP integration.**
- **Don't re-run the Mosquitto Dynamic Security migration through code** — it's done
  manually on the VPS; new PRs only use the existing accounts.
- **Don't assume `deviceId` is UUID-shaped anywhere in new code (§1a).** Don't validate
  it against a UUID regex, don't generate one with `UUID.randomUUID()`, don't size a
  column assuming exactly 36 characters. Treat it as an opaque string with only two
  hard constraints: no `/`, no `|`.
- **Don't apply the string-ID change to `userId`, `certificateId`, `requestId`, or any
  other identifier.** This change is scoped to `deviceId` only.

---

## 13. Documentation deliverables (required per PR)

### 13a. README.md
```bash
grep -n "^## " README.md   # every heading must appear exactly once, before any PR
```

### 13b. Example API calls — `docs/api-examples/`
One numbered shell script per endpoint, `curl` + expected response as comment.

### 13c. Swagger annotations
`@Tag`, `@Operation`, `@ApiResponse` per status code. `@Schema` per DTO field —
**update every `deviceId` field's example value away from a UUID-looking string (§1a).**

---

## 14. Phase 2 scope — Online Transactions & Bayar QR (DELIVERED)

Full detail unchanged from the previous revision of this document — Transfer Online,
Bayar QR Online, Bayar QR Offline, idempotency pattern, and configuration are all
delivered and verified in production. **The only edit needed here going forward is
updating any JSON example in this section that shows `"receiverDeviceId": "uuid"` to
`"receiverDeviceId": "string"`, per §1a.** Do not otherwise re-litigate this section.

---

## 15. MQTT Per-Device Provisioning (Dynamic Security) — DELIVERED

Infrastructure migrated to Mosquitto's Dynamic Security plugin; `MqttAdminClient`
(`@Profile("api")`, separate from the worker's publisher) provisions device MQTT
accounts atomically at registration (mandatory — failure rolls back registration, 503)
and revokes/reinstates on suspend/reactivate (best-effort).

> [!note] Username field, post-§1a
> `MqttAdminClient.provisionDevice(deviceId, deviceToken)` uses `deviceId` directly as
> the MQTT username. Mosquitto's Dynamic Security plugin has no opinion on username
> format — a string identifier works exactly as well as a UUID string did. No change
> needed to `MqttAdminClient` itself beyond ensuring nothing in it assumes UUID shape.

See the full infrastructure detail (Alpine plugin path, folder-mount requirement,
ownership requirement, `exec` vs `run`, Git-commit discipline for config changes) in the
Obsidian vault note `MQTT Dynamic Security` or the standalone
`Panduan-MQTT-Dynamic-Security.docx` — unchanged by this device ID revision.

---

## 16. Known gaps (unchanged by this revision, still open)

- BLE certificate-exchange GATT protocol still needs a final spec handed to firmware —
  **this is now more urgent, not less, since the hardware team is actively making
  device-identity decisions (like this one) and should receive the GATT spec in the
  same conversation.**
- Server public key embedding in firmware, no rotation plan yet.
- Receiving device in an offline transfer gets no MQTT push (proposed fix under
  discussion in the Phase 3 revision negotiation — see the Obsidian vault note
  `Phase 3 - Proposed Enhancements` and the pending revision analysis).