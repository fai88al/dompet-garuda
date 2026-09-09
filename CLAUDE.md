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

## 1b. ⚠️ Device authentication — unified Device-Id-only model (deliberate decision)

> [!warning] This is a documented, intentional product decision — not an oversight
> As of this revision, **every device-facing endpoint uses a single `Device-Id`
> header for identification — no Bearer token, no `Device-Token`, no signature check,
> anywhere.** This supersedes the earlier, narrower R19 decision (which only covered
> the three online synchronous endpoints) — it now applies uniformly to **every**
> device endpoint including `GET /device/balance` and `POST /device/pouch/load`,
> which previously verified a real Bearer token.

**What this means concretely:** `deviceId` is not secret (§1a) — it's meant to be
public. Under this model:
- Anyone knowing a registered `deviceId` can check that device's balance (privacy
  exposure — not just an impersonation risk).
- Anyone knowing a `deviceId` can load a pouch certificate as that device — the
  mechanism that underlies the entire offline BLE trust chain (§3, §4) starts from a
  now-unauthenticated action.
- Anyone knowing a `deviceId` can call the online transfer/payment endpoints as that
  device (unchanged from R19, now just consistently named).

**The offline BLE flow's Ed25519 signature verification (§3, §4, §7 invariant 4)
remains the actual security control for offline settlement** — a fraudulently-loaded
pouch certificate still can't forge a valid signed offline transaction without the
real device's private key. This decision affects **who can call these HTTP
endpoints**, not the cryptographic settlement logic itself.

**This was raised explicitly, twice, to the human developer** — once for the online
endpoints (R19), once for this full expansion — and confirmed both times as the
intended direction for this stage of the product. Do not "fix" this by
reintroducing token verification anywhere. If a task seems to assume any device
endpoint is token-protected, stop and flag the discrepancy rather than assuming
either the code or the task is wrong.

### Header naming — standardize on exactly `Device-Id`, nothing else

Every device endpoint uses the literal header name `Device-Id` (not `device_id`, not
`Receiver-Device-Id`, not `Payer-Device-Id`, not `Authorization: Bearer`). Which
"role" the device plays (sender, receiver, payer) is determined by which endpoint is
called and what's in the request body — not by the header name. This is a rename-only
task for endpoints that already do header-based lookup with a different name/casing,
and an auth-removal task for `/device/balance` and `/device/pouch/load`.

### `DeviceTokenVerifier` / `DeviceTokenService` — now dead code for HTTP auth

These classes are no longer called by any controller for HTTP request authentication.
**Do not delete them** — device tokens are still generated once at registration
(§4) and still reused as the MQTT password (§15), so `DeviceTokenService` remains in
use for that purpose. Just do not wire `DeviceTokenVerifier` into any new endpoint.

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
- **Device — every endpoint** (`/device/sync`, `/device/pouch/load`,
  `/device/balance`, `/device/transfer`, `/device/payment-request*`): single
  `Device-Id` header, no Bearer token, no signature check at the HTTP layer. See §1b
  for the full rationale and consequences.
- **Offline BLE settlement** still verifies Ed25519 signatures — unaffected by §1b,
  see §3, §7 invariant 4.
- **MQTT:** device token still generated at registration, reused as MQTT password —
  unchanged, still relevant despite HTTP auth no longer using it (§1b).

---

## 5. Module layout

```
src/main/java/com/dompetgaruda/api/
  common/          # entities, ledger posting, Ed25519 verification
  config/          # SecurityConfig — /admin/** JWT-protected via AdminTokenFilter;
                   # everything else permitAll() at the Spring Security layer (§1b —
                   # online device endpoints enforce their own header-based device
                   # lookup inside the controller, not via a security filter)
  auth/            # AdminTokenFilter (@Profile("api")), DeviceTokenService (still
                   # used — token generation + MQTT password, §4). DeviceTokenVerifier
                   # is DEAD CODE for HTTP auth as of §1b — do not wire into new endpoints.
  device/          # registration (deviceId + publicKey validation, §1a),
                   # Ed25519PublicKeyValidator, certificate issuance, status admin endpoints
  wallet/          # balance enquiry, top-up, pouch provisioning — Device-Id header auth (§1b)
  ledger/          # LedgerPostingService
  transfer/        # Online transfer — Device-Id header auth (§1b)
  qrpayment/       # Bayar QR online/offline — Device-Id header auth (§1b)
  sync/            # offline sync ingest (api) + settlement (worker) — Device-Id header
                   # auth (§1b) at the API layer; Ed25519 signature verification still
                   # happens at worker settlement time (§3, §7 invariant 4) — unaffected
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

## 17. Phase 3 Feature A — Notification Reconciliation (DELIVERED, known limitation documented)

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

> [!warning] Known limitation — confirmed in production testing, accepted for now (Option C)
> `DELIVERED` is set on a successful MQTT **PUBACK**, which only confirms the broker
> accepted the publish — NOT that any device actually received it. If the receiver
> isn't connected at settlement time, the broker silently drops the message with no
> error, and the row is marked `DELIVERED` anyway — a false positive. This means
> reconnect-triggered reconciliation (step 4) never fires for that transaction, since
> it only re-publishes rows still `PENDING`. **Money is unaffected** — `GET
> /device/balance` never depends on this. The user just silently never gets the push.
> Decision: accepted as known tech debt, to be revisited after Features B and C ship.
> Do not "fix" this silently in an unrelated PR — it needs its own scoped task (most
> likely: check actual broker connection state before marking DELIVERED, or add an
> application-level device acknowledgment instead of trusting PUBACK).

All 7 acceptance criteria and 7 test cases from the signed AC document apply unchanged.

---

## 18. Phase 3 Feature B — Transaction History (DELIVERED — backend + backoffice, live)

Per v1.1 §3.2–§3.4. Both decisions flagged during drafting are resolved (see below).
Backend delivered (PR #38), backoffice UI delivered (PR #14), verified live in
production by the human developer.

### Endpoints

```
GET /device/transactions?page=0&size=20&type=ONLINE_TRANSFER&from=...&to=...
GET /admin/users/{userId}/transactions?page=0&size=20&type=...&from=...&to=...
```

Both pure reads over `ledger_entries`/`ledger_transactions` — no new balance source,
per §7 invariant 1. Pagination mandatory from the first implementation.

**Auth: `Device-Id` header, same as every other device endpoint — resolved, see §1b.**
No ambiguity remains; the three-way auth split that existed when this section was
first drafted has since been unified.

`GET /admin/users/{userId}/transactions` uses standard Admin JWT — no ambiguity there.

### Response shape (per v1.1 §3.2 — all fields required)

```json
{
  "content": [{
    "transactionId": 123,
    "referenceId": "...",
    "type": "ONLINE_TRANSFER",
    "direction": "DEBIT",
    "amount": 50000,
    "counterparty": "...",
    "status": "SUCCESS",
    "notes": "...",
    "createdAt": "2026-09-08T10:15:00Z"
  }],
  "page": 0, "size": 20, "totalElements": 142, "totalPages": 8
}
```

### Status field — cross-system consistency required (v1.1 §3.3)

`SUCCESS` / `PENDING` / `FAILED` / `REVERSED`, derived (not stored redundantly) from
existing state:
- `SUCCESS` — settled `ledger_transactions` row exists.
- `PENDING` — offline transaction uploaded but not yet worker-settled (still in
  `sync_inbox`).
- `FAILED` — flagged, never posted (`flagged_transactions`).
- `REVERSED` — see Open Decision 2.

> [!note] `REVERSED` — real mechanism confirmed needed, but scoped SEPARATELY
> Decided: yes, a real reversal capability is wanted eventually — but explicitly
> **not** as part of this PR. Scope for Feature B stays exactly what it was:
> `REVERSED` is reserved in the enum but never produced by any code path here. The
> actual reversal mechanism (which ledger entries get reversed, how, by whom, what
> triggers it) is a separate, larger design conversation to be scoped later. Do not
> attempt to design or build it as a side effect of this PR — that would blur the
> milestone boundary Feature B is meant to close cleanly.

### Audit log (v1.1 §3.4 — new subsystem)

New `admin_access_log` table: admin identity (from JWT), `userId` accessed,
timestamp, query parameters used. Written on **every** call to
`GET /admin/users/{userId}/transactions`, including empty results — the access
itself is what's audited, not the data returned.
---

## 19. Phase 3 Feature C — Analytics Dashboard (STARTING NOW)

Per the original approved technical scope. Depends on Feature B (§18, delivered) —
shares query shape over `ledger_transactions`, built after it deliberately so query
logic isn't written twice.

### Endpoint

```
GET /admin/analytics/overview?from=...&to=...
```

Standard Admin JWT — same pattern as every other `/admin/**` endpoint, no ambiguity.
**Read-only, always** — must never write to the ledger under any circumstance, and
query cost must not degrade live transaction processing performance.

### Metrics (per the approved v1.1 scope)

```json
{
  "dailyVolume": [
    { "date": "2026-09-01", "type": "ONLINE_TRANSFER", "count": 12, "totalAmount": 600000 }
  ],
  "typeDistribution": [
    { "type": "OFFLINE_TRANSFER", "count": 340 }
  ],
  "statusCounts": {
    "SUCCESS": 512, "PENDING": 3, "FAILED": 8, "REVERSED": 0
  },
  "activeUsers": { "daily": 12, "sevenDay": 45, "thirtyDay": 89 },
  "deviceStatus": { "ACTIVE": 45, "SUSPENDED": 3, "LOCKED": 1 }
}
```

> [!note] `REVERSED` will always be `0` right now
> Per §18's resolved decision, no reversal mechanism exists yet — this count exists
> in the shape for forward compatibility but will always report zero until that
> separate future task ships. Do not treat a non-zero value here as expected;
> flag it if one ever appears, since it would mean something unexpected happened.

### Query approach

Direct SQL for aggregation, not ORM-generated — consistent with this project's
existing money-query philosophy (§2, §7). Three logical query groups:

1. **Daily volume by type** — `GROUP BY DATE(created_at), type` over
   `ledger_transactions` within the requested date range.
2. **Type distribution** — `GROUP BY type` count over the same range.
3. **Device status counts** — simple `GROUP BY status` over `devices`, no date
   filter (current snapshot, not historical).

Active user counts (daily/7-day/30-day) require a distinct-user query over
transactions in each window — confirm the exact definition of "active" (any
transaction? Any login? Device activity?) with the human developer before
implementing if not already unambiguous from context — this is the one metric
without an obvious single interpretation.

### Performance

No caching or materialized views for this pass — direct queries are acceptable at
current data volume. If aggregate queries start measurably affecting live
transaction latency as data grows, that's a follow-up optimization task, not
something to preemptively build now.