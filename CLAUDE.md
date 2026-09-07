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

The product also supports **online, server-mediated transactions**: direct online transfer
between users, and "Bayar QR" — a QR-code-based payment flow, both online and offline.
**"Bayar QR" is NOT the national QRIS standard.** Never call it "QRIS" in code, docs, or UI copy.

**Stage: prototype.** Prefer simple, correct, auditable code over cleverness or premature scale.

---

## 1a. Device ID format — string, not UUID (delivered)

`deviceId` is a plain string (`VARCHAR(128)`), sourced from the hardware team's own scheme
(MAC-derived), not a server-generated UUID v4. Must never contain `/` or `|` — enforced at
both application level (400 on registration) and database level (CHECK constraint). This
affects `deviceId` only — `userId`, `certificateId`, `requestId` remain UUID.

`publicKey` is validated as Base64(X.509 SubjectPublicKeyInfo DER) — a fixed 44-byte
structure, not raw 32 bytes — matching exactly what `Ed25519Verifier` expects at signature
verification time. Both backend (`Ed25519PublicKeyValidator`) and backoffice (real-time
form feedback) enforce this identically.

---

## 1b. ⚠️ Online endpoint authentication — deliberate decision, read carefully

> [!warning] This is a documented, intentional product decision — not an oversight
> As of the commit that introduced `receiverDeviceId`/`Receiver-Device-Id`/
> `Payer-Device-Id`, **online synchronous endpoints (`POST /device/transfer`,
> `POST /device/payment-request`, `POST /device/payment-request/{id}/pay`) no longer
> require a Bearer device token.** Authentication is now a plain `device_id` /
> `Receiver-Device-Id` / `Payer-Device-Id` HTTP header, looked up directly against
> `devices` with no secret or signature check of any kind.

**What this means concretely:** `deviceId` is not secret by design (§1a) — it's meant to be
public, visible in MQTT topics, QR payloads, logs. Under this model, **anyone who knows a
registered device's ID can call these three endpoints as that device**, with nothing to
prove ownership. This is architecturally different from the offline BLE flow (§3, §4),
where Ed25519 signatures remain the actual security control — this decision only affects
the three synchronous online endpoints listed above.

**This was raised explicitly to the human developer, who confirmed this is the intended
direction for this stage of the product.** Do not "fix" this by silently reintroducing
Bearer-token auth on these endpoints — if asked to touch these controllers for an unrelated
reason, leave the auth model as-is unless explicitly instructed otherwise. If a future task
seems to assume these endpoints are token-protected, stop and flag the discrepancy rather
than assuming either the code or the task description is wrong.

---

## 2. Tech stack (fixed — do not substitute without being asked)

- **Language:** Java 21 (LTS)
- **Framework:** Spring Boot 3.x
- **Build:** Maven (`./mvnw`)
- **DB:** PostgreSQL 16, Flyway migrations, `ddl-auto=validate`
- **Persistence:** Spring Data JPA for simple reads; **plain SQL / JdbcTemplate for all
  ledger and money writes.**
- **MQTT client:** Eclipse Paho
- **Scheduled-job locking:** ShedLock on every `@Scheduled` method
- **Tests:** JUnit 5 + Testcontainers (real Postgres, real Mosquitto)
- **API docs:** Springdoc OpenAPI, Swagger UI at `/swagger-ui.html`, api profile only
- **Password hashing:** BCrypt, cost factor 10
- **JWT:** `io.jsonwebtoken` — **admin/writer auth only** (§4). Not used for device
  auth on the online endpoints as of §1b.

> **Package root:** `com.dompetgaruda.api`.

---

## 3. Architecture

**One image, two profiles** (`api`, `worker`) — see prior revisions for full detail,
unchanged. Profile isolation rule still applies: any bean referencing admin config or
`MqttAdminClient` must be `@Profile("api")`.

**Two settlement models, still architecturally distinct:**
- Offline (BLE): worker settles async via `sync_inbox`, Ed25519 signatures are the
  security control.
- Online (Transfer/Bayar QR): API posts synchronously; **auth is now header-based
  device lookup, not Bearer token (§1b)** — this is the one thing that changed from
  earlier revisions of this document.

**Ledger posting reference** — unchanged, see prior revisions for the full table
(`TOPUP`, `POUCH_LOAD`, `OFFLINE_TRANSFER`, `POUCH_REFUND`, `ONLINE_TRANSFER`,
`QR_PAYMENT_ONLINE`).

---

## 4. Authentication

- **Admin/writer:** unchanged — JWT via `POST /admin/auth/login`, `@Profile("api")` only.
- **Device — offline endpoints (`/device/sync`, `/device/pouch/load`):** Bearer device
  token, unchanged, still the primary check backed by Ed25519 signatures at settlement.
- **Device — online endpoints (`/device/transfer`, `/device/payment-request*`):**
  `device_id`-style header only, no Bearer token, no signature. See §1b.
- **MQTT:** device token reused as MQTT password, unchanged.

---

## 5. Module layout

```
src/main/java/com/dompetgaruda/api/
  common/          # entities, ledger posting, Ed25519 verification
  config/          # SecurityConfig — /admin/** JWT-protected via AdminTokenFilter;
                   # everything else permitAll() at the Spring Security layer (§1b —
                   # online device endpoints enforce their own header-based device
                   # lookup inside the controller, not via a security filter)
  auth/            # AdminTokenFilter (@Profile("api")), DeviceTokenService,
                   # DeviceTokenVerifier (still used by offline endpoints only)
  device/          # registration (deviceId + publicKey validation, §1a),
                   # Ed25519PublicKeyValidator, certificate issuance, status admin endpoints
  wallet/          # balance enquiry, top-up, pouch provisioning
  ledger/          # LedgerPostingService
  transfer/        # Online transfer — device_id header auth (§1b)
  qrpayment/       # Bayar QR online/offline — Receiver-Device-Id / Payer-Device-Id
                   # header auth (§1b)
  sync/            # offline sync ingest (api) + settlement (worker) — unchanged,
                   # still Bearer-token + Ed25519 signature verified
  reconciliation/  # PouchReconciliationJob (worker) — NOTE: this is the existing
                   # pouch-vs-ledger job, NOT the same as Feature A's notification
                   # reconciliation (§17) — do not conflate the two
  mqtt/            # Paho publisher (worker) + MqttAdminClient (api, provisioning)
  admin/           # resolve flags, etc.
  article/         # article CRUD
src/main/resources/
  db/migration/    # Flyway — includes device_id VARCHAR(128) migration
  application.yml / application-api.yml / application-worker.yml
```

---

## 6. Configuration & local development

Unchanged — no secrets in committed files, `.env.example` committed, recurring
crash-loop failure mode if a new required env var isn't added to both `.env` and
`docker-compose.prod.yml` before deploy.

---

## 7. MONEY-SAFETY INVARIANTS (read twice)

1–12 unchanged from prior revisions — no mutable balance column, balanced double-entry,
`BIGINT` money, DB-level idempotency, worker-only offline ledger posting, pouch outflow
caps, ShedLock on scheduled jobs, MQTT carries no financial authority, no secret
logging, Flyway-only schema changes, flag-don't-drop, named config properties.

> [!note] §1b does not violate these invariants
> The online-endpoint auth change (§1b) affects **who can initiate** a transaction, not
> the ledger's internal correctness. Idempotency (invariant 4), balanced double-entry
> (invariant 2), and every other invariant here still hold regardless of how the
> `deviceId` in the request was authenticated. This is a real, separate risk (someone
> could impersonate a device), just not one that breaks these specific invariants.

---

## 8–16. Unchanged sections

MQTT topic contract, commands reference, testing expectations, git workflow,
prohibitions list, documentation deliverables, Phase 2 scope (delivered), MQTT Dynamic
Security provisioning (delivered), and known gaps — all unchanged from prior revisions.
One addition to the prohibitions list (§12): **don't silently revert the §1b auth
decision.**

---

## 17. Phase 3 Feature A — Notification Reconciliation (STARTING NOW)

Approved in proposal revision v1.1, acceptance criteria agreed with Faisal
(`AC-TestCase-Fitur-A-Notifikasi-Rekonsiliasi.docx`). Not yet implemented — this is the
current milestone.

**Design (unchanged from when this was originally specified):**

1. New table `notification_log`: `id`, `offline_transaction_id` (FK), `device_id`
   (receiver, `VARCHAR(128)` FK — matches §1a), `status`
   (`PENDING`/`DELIVERED`/`EXPIRED`), `created_at`, `delivered_at`, `expires_at`.
2. Config: `notification.reconciliation.expiry-days`, default `3`
   (`NOTIFICATION_RECONCILIATION_EXPIRY_DAYS`), agreed with the client at 3 days.
3. Worker settlement flow inserts a `PENDING` row and attempts
   `wallet/{receiverDeviceId}/payment-received` publish for every `OFFLINE_TRANSFER`.
4. Reconciliation check piggybacks on existing authenticated device traffic — no new
   "online now" ping endpoint. Re-publishes any `PENDING` rows for that device.
5. Scheduled sweep job (`NotificationExpiryJob`, ShedLock, hourly) marks stale
   `PENDING` rows `EXPIRED` — belt-and-suspenders alongside the reconnect check.
6. **`GET /device/balance` never references `notification_log`, ever** — balance
   correctness must be fully independent of notification delivery (§7 invariant 8).
   This is the single most important test in the eventual PR.

All 7 acceptance criteria and 7 test cases from the signed AC document apply unchanged.