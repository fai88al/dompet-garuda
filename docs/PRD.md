# Product Requirements Document — Dompet Digital (Dompet Garuda)

| | |
|---|---|
| **Project** | Dompet Digital — offline-capable IoT payment device |
| **Initiator** | Faisal (via Fastwork) |
| **Stage** | Prototype / proof-of-concept — Phase 2 delivered and verified |
| **Doc owner** | Backend team |
| **Status** | Phase 1 delivered. Phase 2 (online transactions & Bayar QR) delivered, deployed, and manually verified against production. MQTT per-device provisioning is in progress (infrastructure done, application PR pending). |

---

## 1. Problem & premise

Digital payments in Indonesia assume connectivity. Dompet Digital is a hardware wallet that
lets two people transfer value device-to-device over Bluetooth with no internet, settling
with the server later when connectivity returns.

**Phase 2** extended the product to also support transactions when connectivity *is*
available — direct online transfer, and QR-assisted payments both online and offline —
without abandoning the offline-first capability that is the product's core differentiator.
**All three Phase 2 features are now delivered.**

---

## 2. Goals

**Phase 1 (delivered):**
- **G1.** Complete offline value transfer between two devices over BLE, correctly settled after reconnect.
- **G2.** Prevent offline double-spending (offline-pouch model).
- **G3.** Prove the backend architecture (API + worker, transactional inbox, double-entry ledger) end to end.
- **G4.** Demonstrable on a single Hostinger KVM2 server.

**Phase 2 (delivered):**
- **G5.** Enable direct online transfer between users without requiring BLE or a pouch. **Met.**
- **G6.** Enable QR-assisted payments — both online (server-mediated) and offline
  (BLE-mediated) — so users can pay by scanning instead of manual entry. **Met.**
- **G7.** Preserve every money-safety invariant from Phase 1 across the new synchronous
  online flows — no relaxed guarantees just because a flow is "simpler." **Met** — verified
  by direct production testing (idempotency replay produces zero duplicate ledger rows;
  self-transfer, over-limit, and expired-request rejections all confirmed).

## 2a. Non-goals

- **NG1.** Not production-grade security or compliance.
- **NG2.** Not real money or a real payment network. Balances are prototype tokens in IDR.
- **NG3.** No multi-hop offline re-spend.
- **NG4.** No consumer mobile app; admin/writer backoffice + landing page only.
- **NG5.** No horizontal scaling or multi-region.
- **NG6.** No KYC or dispute resolution.
- **NG7.** Balance enquiry returns current figures only — no history or statements.
- **NG8.** No further writer/article features beyond what's delivered (article CRUD, public read).
- **NG9. "Bayar QR" is NOT QRIS.** No integration with Bank Indonesia's QRIS standard, no
  bank or PJP (Penyedia Jasa Pembayaran) integration, no interoperability with external
  e-wallets. QR codes are generated and scanned entirely within the Dompet Garuda ecosystem;
  settlement happens in this system's own ledger only. This naming and scope boundary is
  deliberate — see CLAUDE.md §1 and §12.
- **NG10.** No hardware/firmware work in this document's costed scope, except the QR
  payload specification handed to the firmware team (§4.5c) and the BLE protocol draft
  (§9, R15). Camera integration, QR rendering on-device, BLE stack implementation, and
  scanning UX are firmware responsibilities, separately scoped.

---

## 3. Users

- **Device holder.** Owns a Dompet device; checks balance; tops up; transacts offline; now
  also transacts online and via Bayar QR.
- **Admin.** Registers devices, tops up balances, manages users and flags via backoffice.
- **Writer.** Manages articles via backoffice (unrelated to Phase 2, delivered previously).

---

## 4. In-scope features

### Phase 1 (delivered)
- §4.1 Device registration & identity
- §4.2 Online top-up (admin-initiated)
- §4.2a Balance enquiry — "Cek Saldo"
- §4.3 Offline pouch provisioning
- §4.4 Offline transfer over BLE
- §4.5 Offline QRIS-style request — **superseded by §4.5c below**; the cosmetic-only
  version described in the original Phase 1 PRD is retired in favor of the real implementation.
- §4.6 Sync & settlement
- §4.7 Reconciliation job
- §4.8 Admin read endpoints
- Real per-user admin/writer authentication (JWT)
- Article CRUD + public read endpoints

### Phase 2 — DELIVERED

#### 4.5a Transfer Online Antar Pengguna — DELIVERED
- User sends money directly to another user's online balance via the server. No BLE, no
  pouch, no certificate involved.
- Idempotency-protected: retrying an ambiguous request never double-posts. **Verified**
  against production by replaying an identical request with the same idempotency key and
  confirming a single ledger posting.
- Self-transfer is rejected. **Verified.**
- Subject to a configurable maximum amount per transaction (default **Rp 10,000,000**,
  see CLAUDE.md §14.5). **Verified** by submitting an over-limit amount and confirming
  rejection.

#### 4.5b Bayar QR Online — DELIVERED
- Receiver generates a payment request (amount) on their device; the device renders it as
  a QR code **locally, from a text payload the server provides** — the server never
  generates an image.
- Payer scans the QR with their device's camera, sees the amount, confirms with PIN.
- Settlement is a direct server-mediated ledger posting — same integrity model as §4.5a,
  initiated via the two-step request/pay flow.
- Payment requests expire after a configurable TTL (default **10 minutes**) and can only be
  paid once (nonce-protected against reuse). **Verified**: paying an already-paid request
  returns 409; the scheduled expiry sweep (`PaymentRequestExpiryJob`) is confirmed running
  every minute in production logs.

#### 4.5c Bayar QR Offline — DELIVERED (backend portion)
- Functionally, this is the **existing offline BLE Transfer flow (§4.4)** with a QR-based
  shortcut for entering payment details. The receiver's device shows a QR; the payer scans
  it with their camera; the two devices then complete the transfer over Bluetooth exactly as
  in §4.4 — same mutual authentication, same Ed25519 signing, same settlement.
- The backend's role was minimal: a QR payload specification for the firmware team, and an
  `origin` field on `offline_transactions` for observability (BLE vs QR-initiated).
  **Confirmed by code review**: `origin` never appears in any verification/trust branch —
  only in the final insert statement, after all checks have passed.
- This supersedes the placeholder "offline QRIS-style request" described in the original
  Phase 1 PRD §4.5.

---

## 5. Out of scope

Real QRIS/bank/PJP integration, consumer mobile app, hardware procurement, monthly
infrastructure costs, third-party security audits, large-scale load testing — see NG1–NG10.

---

## 6. Functional requirements

> FR numbering continues from Phase 1. FR1–FR17 are Phase 1 (delivered). FR18 onward is Phase 2.

### Phase 1 (delivered — kept for reference, do not renumber)
- **FR1–FR14.** Device registration, top-up, pouch provisioning, offline transfer, sync
  ingest, settlement, reconciliation, admin reads, balance enquiry.
- **FR15.** Admin/writer login (JWT).
- **FR16.** Resolve flagged transaction.
- **FR17.** Update device status (ADMIN action).

### Phase 2 — DELIVERED

- **FR18.** `POST /device/transfer` creates a balanced `ONLINE_TRANSFER` ledger posting when:
  device token valid, `Idempotency-Key` header present, receiver exists, receiver != sender,
  amount > 0 and ≤ configured max, sender balance sufficient. Returns 200 with new sender
  balance. Rejects self-transfer with 400, insufficient balance with 422, missing idempotency
  key with 400. **Status: delivered, verified in production.**
- **FR19.** A duplicate `Idempotency-Key` for the same device on `/device/transfer` returns
  the original response without creating a second ledger posting (enforced by a `UNIQUE`
  constraint on the `idempotency_keys` table). **Status: delivered, verified in production**
  via direct row-count comparison before/after a replayed call.
- **FR20.** `POST /device/payment-request` creates a `PENDING` payment request with a
  unique nonce, a configurable expiry (default 10 minutes), and a QR-encodable text payload.
  **Status: delivered, verified in production.**
- **FR21.** `POST /device/payment-request/{id}/pay` settles a `PENDING`, non-expired
  request as a balanced `QR_PAYMENT_ONLINE` posting, marks it `PAID`. Rejects: unknown
  request (404), already-paid request (409), expired-at-check-time request (410, marks it
  `EXPIRED`), self-payment (400), insufficient balance (422), missing idempotency key (400),
  duplicate idempotency key (returns original result). **Status: delivered, verified in
  production** — 404, 409, and the idempotency-replay guarantee were all directly tested
  against the live API.
- **FR22.** A scheduled job (`PaymentRequestExpiryJob`, ShedLock-guarded) marks `PENDING`
  payment requests past their `expiresAt` as `EXPIRED` at least once per minute, independent
  of the pay endpoint's own real-time expiry check. **Status: delivered** — confirmed
  running on schedule in production logs. Real-time expiry-path testing (410 response,
  actually letting a request lapse) has not yet been separately exercised against production
  — only via CI tests. **Follow-up recommended.**
- **FR23.** Offline transactions carry an `origin` field (`BLE` or `QR`), defaulting to
  `BLE`. Settlement logic, signature verification, and counter/replay checks are identical
  regardless of origin. **Status: delivered, confirmed by code review and CI test** (identical
  ledger postings and identical over-limit flagging behavior for both origin values).
- **FR24.** `transfer.online.max-amount-idr` and `qr-payment.request-ttl-minutes` are
  required, environment-configurable properties with documented defaults (Rp 10,000,000
  and 10 minutes respectively). No hardcoded fallback exists for the money-safety limit.
  **Status: delivered, live on the VPS `.env`.**

### Phase 2 — MQTT Provisioning (in progress)

- **FR25.** `POST /admin/devices` provisions MQTT credentials for the new device
  (username=deviceId, password=the same device token) as a mandatory part of registration.
  Failure to provision rolls back the entire registration (503). **Status: delivered.**
  `MqttAdminClient` (`@Profile("api")`) drives the Mosquitto Dynamic Security control API;
  a provisioning failure throws and rolls back the whole registration transaction, mapped to
  503 at the controller.
- **FR26.** `PATCH /admin/devices/{deviceId}/status` revokes the device's MQTT access when
  status changes to `SUSPENDED`/`LOCKED`, and restores it when status returns to `ACTIVE`.
  A failure at this step must not block the status change itself. **Status: delivered.**
  Runs strictly after the status row commits, wrapped in a try/catch that swallows every
  exception and logs a WARNING — an MQTT/broker outage never blocks the status change.

---

## 7. Technical constraints

- Hostinger KVM2: 2 vCPU, 8 GB RAM, Ubuntu 24.04.
- Backend: Java 21 / Spring Boot 3.x / PostgreSQL 16 / Mosquitto / Caddy / Docker Compose.
- Backoffice: Next.js 16 / Bun / shadcn/ui.
- Landing page: Next.js 16, SEO-first, public article API.
- ESP32 firmware: C/C++, BLE + Ed25519 on-device (firmware team, separately scoped —
  Bayar QR camera/scan/render work included; BLE protocol itself still needs finalizing,
  see §9 R15).
- CI/CD: GitHub Actions → GHCR → VPS deploy via SSH on push to `main`, one pipeline per repo.
- Online endpoints add no new infrastructure — they run in the existing `api` container,
  synchronous, no new worker responsibilities except the payment-request expiry job.
- **New:** Mosquitto now runs the Dynamic Security plugin rather than static
  password/ACL files — see CLAUDE.md §15 for the full infrastructure detail (folder mount
  requirements, ownership requirements, administrative command patterns).

---

## 8. Success criteria

**Phase 1 (met):**
1. Two devices complete an offline transfer with no internet; settles correctly after reconnect.
2. Replayed batch creates no duplicate balance.
3. Over-limit or tampered batch caught and flagged, not posted.
4. Cek Saldo figures reconcile correctly across offline-spend-then-sync cycle.
5. Full stack runs within 8 GB without swapping under demo load.
6. Backup can be restored (tested at least once).
7. Admin can complete the full workflow (create user, register device, top up, view flags)
   from the backoffice UI alone.

**Phase 2 (met, with direct production evidence):**
8. **Met.** A user can transfer online directly to another user, and the receiving user's
   balance reflects it immediately, without any BLE or pouch involvement. Verified via
   `POST /device/transfer` against the live API; sender/receiver balances checked before
   and after.
9. **Met.** A duplicate submission of the same online transfer never results in the money
   moving twice. Verified by submitting the identical request with the same
   `Idempotency-Key` twice against production and confirming: (a) identical response body
   both times, (b) `SELECT COUNT(*) FROM ledger_transactions WHERE type = 'ONLINE_TRANSFER'`
   returned `1`, not `2`.
10. **Met.** A Bayar QR Online payment request that is paid once cannot be paid again.
    Verified: a second payment attempt (with a fresh idempotency key) against an
    already-`PAID` request returned 409 against production.
11. **Partially met.** A Bayar QR Online payment request past its expiry is rejected (410)
    — confirmed via CI test. Not yet independently re-verified against production by
    letting a live request actually lapse past its TTL (only the "already paid" 409 path
    and the scheduled-sweep log output were checked live). **Recommended before declaring
    this fully closed.**
12. **Met.** A Bayar QR Offline transaction settles through the identical verification path
    as a manually-initiated BLE transfer. Confirmed via code review (origin never gates a
    verification branch) and CI test (identical ledger postings, identical over-limit
    flagging for both origin values).

---

## 9. Decisions & risks

### Phase 1 (resolved, kept for reference)
- R1–R7, Q1–Q4, §9a/§9b as previously documented — unchanged, still in force. Max pouch
  Rp 3,000,000, 24h certificate validity, no multi-hop offline re-spend, etc.

### Phase 2 — resolved

- **R8: Idempotency key ownership. DECIDED — device-generated.** The device generates the
  `Idempotency-Key`, because only the device knows whether a given request is a genuine
  retry of an ambiguous prior attempt or an intentionally new transaction.
- **R9: Self-transfer. DECIDED — always rejected.** Both `/device/transfer` and paying
  one's own `/device/payment-request` are rejected with 400.
- **R10: Online transfer maximum amount. DECIDED — Rp 10,000,000, configurable.** A round,
  generous ceiling bounding the damage of any single error, bug, or abuse case — online
  transfers are always server-verified in real time, unlike the offline pouch limit (which
  exists because a lost device is unrecoverable). **Must be revisited before any real-money
  deployment** — no documented business rationale beyond "a safe round number."
- **R11: Bayar QR Offline reuses the existing BLE trust model.** No new cryptographic
  design was needed — the QR is purely a data-entry shortcut, confirmed explicitly with the
  client to avoid the more complex alternative of encoding a fully signed transaction into
  the QR itself.
- **R12: Naming — "Bayar QR" not "QRIS".** Explicit client decision; QRIS is a registered
  Bank Indonesia standard and using the name for a non-interoperable in-ecosystem feature
  risks user confusion and regulatory scrutiny. See NG9.
- **R13: MQTT device credentials. DECIDED — reuse the device token, mint no new secret.**
  Username = deviceId, password = the existing device token. Avoids a second secret surface
  to manage and protect.
- **R14: MQTT provisioning failure handling. DECIDED — provisioning is mandatory at
  registration (fails the whole registration on error); revoke/reinstate at suspend/lock is
  best-effort (logs a warning, never blocks the status change).** Rationale: a silently
  failed provisioning creates a device that can never be notified, permanently — a serious,
  invisible defect. A failed revoke during an emergency suspend must not block a more
  urgent security action (locking a lost or stolen device).

### Phase 2 — newly identified, unresolved

- **R15: BLE certificate-exchange protocol is undefined.** Surfaced while documenting the
  offline transfer flow end-to-end. A draft GATT structure has been proposed to the
  firmware team but not finalized. See CLAUDE.md §16.1.
- **R16: Server public-key distribution to firmware has no rotation plan.** The server's
  Ed25519 public key must be embedded in firmware at flash time for offline certificate
  verification to work; rotating the corresponding private key later requires an OTA
  update campaign across all fielded devices. Not an immediate blocker, but must be an
  input to any future security incident-response or key-rotation plan. See CLAUDE.md §16.2.
- **R17: The receiving device in an offline transfer gets no MQTT push notification** —
  only the sender does. Not a correctness issue, but an inconsistency relative to the
  `payment-received` pattern already built for the online flows. Not yet scoped as a fix.
  See CLAUDE.md §16.4.

---

## 10. Milestones

### Phase 1 — Complete
All PRs (scaffold through admin read endpoints, real auth, articles, backoffice, landing
page, infrastructure) merged, deployed, and verified in production as of Phase 1 close.

### Phase 2 — Online Transactions & Bayar QR

| # | Task | Status |
|---|------|--------|
| 1 | RAB & Proposal drafted, reviewed | ✅ done |
| 2 | RAB & Proposal approved by Faisal | ✅ done |
| 3 | Invoice issued | ✅ done |
| 4 | CLAUDE.md / PRD.md updated for Phase 2 scope | ✅ done |
| 5 | Transfer Online (FR18, FR19) | ✅ **delivered, verified in production** |
| 6 | Bayar QR Online (FR20–FR22) | ✅ **delivered, verified in production** |
| 7 | Bayar QR Offline — backend portion (FR23) | ✅ **delivered, verified via code review + CI** |
| 8 | Device simulator updated for new flows | pending |
| 9 | End-to-end testing across all Phase 2 features | ✅ **done for the core happy/failure paths** (see §8); expiry-lapse path (item 11 above) still recommended |
| 10 | Documentation updates (README, MQTT contract, API examples) | ✅ done — `docs/MQTT_CONTRACT.md` added, README milestones updated for FR25/FR26 |
| 11 | Production deployment & verification | ✅ **done** for all three features |
| 12 | Payment received (Bukti Pembayaran finalized) | ✅ done — payment cleared |

### Phase 2b — MQTT Per-Device Provisioning (new, in progress)

| # | Task | Status |
|---|------|--------|
| 1 | Identify the gap (no device MQTT credentials existed) | ✅ done |
| 2 | Design decision: reuse device token, no new secret (R13) | ✅ done |
| 3 | Migrate Mosquitto to Dynamic Security plugin on VPS | ✅ done, manually |
| 4 | Create `admin`, `dompet-worker`, `dompet-api-admin` accounts + `worker-role`, `device-role` | ✅ done |
| 5 | Verify worker survives a full broker restart on the new system | ✅ done |
| 6 | `MqttAdminClient` bean + `POST /admin/devices` integration (FR25) | ✅ delivered |
| 7 | `PATCH /admin/devices/{deviceId}/status` integration (FR26) | ✅ delivered |
| 8 | Rotate all MQTT passwords to distinct, strong values | pending (tech debt, tracked in CLAUDE.md §15) |

### Known follow-up items (not yet scheduled)

- BLE certificate-exchange GATT protocol finalization with firmware team (R15).
- Server public-key rotation / OTA update plan (R16).
- Receiving-device MQTT push notification for offline transfers (R17).
- Device simulator update (Phase 2 item 8).
- **`docker-compose.prod.yml`'s `api` service `environment:` block does not yet forward
  `MQTT_BROKER_URL` / `MQTT_API_ADMIN_USERNAME` / `MQTT_API_ADMIN_PASSWORD` (CLAUDE.md §6's
  recurring failure mode). The PR delivering FR25/FR26 (`MqttAdminClient`) deliberately did
  NOT touch `docker-compose.prod.yml` per its stated scope — this must be added before the
  next deploy that includes that PR, or the api container will crash-loop.