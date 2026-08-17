# Product Requirements Document — Dompet Digital (Dompet Garuda)

| | |
|---|---|
| **Project** | Dompet Digital — offline-capable IoT payment device |
| **Initiator** | Faisal (via Fastwork) |
| **Stage** | Prototype / proof-of-concept — Phase 2 (online transactions) approved |
| **Doc owner** | Backend team |
| **Status** | Phase 1 delivered. Phase 2 scope locked via approved RAB/Proposal, Aug 2026. |

---

## 1. Problem & premise

Digital payments in Indonesia assume connectivity. Dompet Digital is a hardware wallet that
lets two people transfer value device-to-device over Bluetooth with no internet, settling
with the server later when connectivity returns.

**Phase 2 (this update)** extends the product to also support transactions when connectivity
*is* available — direct online transfer, and QR-assisted payments both online and offline —
without abandoning the offline-first capability that is the product's core differentiator.

---

## 2. Goals

**Phase 1 (delivered):**
- **G1.** Complete offline value transfer between two devices over BLE, correctly settled after reconnect.
- **G2.** Prevent offline double-spending (offline-pouch model).
- **G3.** Prove the backend architecture (API + worker, transactional inbox, double-entry ledger) end to end.
- **G4.** Demonstrable on a single Hostinger KVM2 server.

**Phase 2 (this update):**
- **G5.** Enable direct online transfer between users without requiring BLE or a pouch.
- **G6.** Enable QR-assisted payments — both online (server-mediated) and offline (BLE-mediated) — so users can pay by scanning instead of manual entry.
- **G7.** Preserve every money-safety invariant from Phase 1 across the new synchronous online flows — no relaxed guarantees just because a flow is "simpler."

## 2a. Non-goals

- **NG1.** Not production-grade security or compliance.
- **NG2.** Not real money or a real payment network. Balances are prototype tokens in IDR.
- **NG3.** No multi-hop offline re-spend.
- **NG4.** No consumer mobile app; admin/writer backoffice + landing page only.
- **NG5.** No horizontal scaling or multi-region.
- **NG6.** No KYC or dispute resolution.
- **NG7.** Balance enquiry returns current figures only — no history or statements.
- **NG8.** No further writer/article features beyond what's delivered (article CRUD, public read).
- **NG9 (Phase 2). "Bayar QR" is NOT QRIS.** No integration with Bank Indonesia's QRIS
  standard, no bank or PJP (Penyedia Jasa Pembayaran) integration, no interoperability with
  external e-wallets. QR codes are generated and scanned entirely within the Dompet Garuda
  ecosystem; settlement happens in this system's own ledger only. This naming and scope
  boundary is deliberate — see CLAUDE.md §1 and §12.
- **NG10 (Phase 2).** No hardware/firmware work in this document's costed scope, except the
  QR payload specification handed to the firmware team (§4.5c). Camera integration, QR
  rendering on-device, and scanning UX are firmware responsibilities, separately scoped.

---

## 3. Users

- **Device holder.** Owns a Dompet device; checks balance; tops up; transacts offline; now
  also transacts online and via Bayar QR.
- **Admin.** Registers devices, tops up balances, manages users and flags via backoffice.
- **Writer.** Manages articles via backoffice (unrelated to Phase 2, delivered previously).

---

## 4. In-scope features

### Phase 1 (delivered — see Milestones §10 for status)
- §4.1 Device registration & identity
- §4.2 Online top-up (admin-initiated)
- §4.2a Balance enquiry — "Cek Saldo"
- §4.3 Offline pouch provisioning
- §4.4 Offline transfer over BLE
- §4.5 Offline QRIS-style request — **superseded by Phase 2's more precise "Bayar QR
  Offline" (§4.5c below); the cosmetic-only version described in the original Phase 1 PRD
  is retired in favor of the real implementation.**
- §4.6 Sync & settlement
- §4.7 Reconciliation job
- §4.8 Admin read endpoints
- Real per-user admin/writer authentication (JWT)
- Article CRUD + public read endpoints

### Phase 2 — NEW (this update)

#### 4.5a Transfer Online Antar Pengguna
- User sends money directly to another user's online balance via the server. No BLE, no
  pouch, no certificate involved.
- Requires the sending device to be online (obviously — this is the point of the feature).
- Idempotency-protected: retrying an ambiguous request never double-posts.
- Self-transfer is rejected.
- Subject to a configurable maximum amount per transaction (default **Rp 10,000,000**,
  see CLAUDE.md §14.5 — chosen because there is no pouch-style loss-containment for
  online transfers, so a sane ceiling limits blast radius of any single error or abuse case,
  while remaining well above typical transaction sizes).

#### 4.5b Bayar QR Online
- Receiver generates a payment request (amount) on their device; the device renders it as
  a QR code.
- Payer scans the QR with their device's camera, sees the amount, confirms with PIN.
- Settlement is a direct server-mediated ledger posting — same integrity model as §4.5a,
  initiated via the two-step request/pay flow.
- Payment requests expire after a configurable TTL (default **10 minutes**) and can only be
  paid once (nonce-protected against reuse).

#### 4.5c Bayar QR Offline
- Functionally, this is the **existing offline BLE Transfer flow (§4.4)** with a QR-based
  shortcut for entering payment details. The receiver's device shows a QR; the payer scans
  it with their camera; the two devices then complete the transfer over Bluetooth exactly as
  in §4.4 — same mutual authentication, same Ed25519 signing, same settlement.
- The backend's role is minimal: a QR payload specification for the firmware team, and an
  `origin` field on `offline_transactions` for observability (BLE vs QR-initiated). No new
  settlement logic, no new verification path.
- **This supersedes the placeholder "offline QRIS-style request" described in the original
  Phase 1 PRD §4.5** — that description was written before the real design was finalized
  and should not be treated as authoritative.

---

## 5. Out of scope

Real QRIS/bank/PJP integration, consumer mobile app, hardware procurement, monthly
infrastructure costs, third-party security audits, large-scale load testing — see NG1–NG10.

---

## 6. Functional requirements

> FR numbering continues from Phase 1. FR1–FR17 are Phase 1 (delivered). FR18 onward is Phase 2.

### Phase 1 (delivered — kept for reference, do not renumber)
- **FR1–FR14.** Device registration, top-up, pouch provisioning, offline transfer, sync
  ingest, settlement, reconciliation, admin reads, balance enquiry. (Full text: see repo
  history / prior PRD revisions — unchanged, still in force.)
- **FR15.** Admin/writer login (JWT).
- **FR16.** Resolve flagged transaction.
- **FR17.** Update device status (ADMIN action).

### Phase 2 — NEW

- **FR18.** `POST /device/transfer` creates a balanced `ONLINE_TRANSFER` ledger posting
  (DEBIT sender.ONLINE, CREDIT receiver.ONLINE) when: device token valid, `Idempotency-Key`
  header present, receiver exists, receiver != sender, amount > 0 and ≤ configured max,
  sender balance sufficient. Returns 200 with new sender balance. Rejects self-transfer
  with 400. Rejects insufficient balance with 422. Rejects missing idempotency key with 400.
- **FR19.** A duplicate `Idempotency-Key` for the same device on `/device/transfer` returns
  the original response without creating a second ledger posting (enforced by a `UNIQUE`
  DB constraint, not application logic alone).
- **FR20.** `POST /device/payment-request` creates a `PENDING` payment request with a
  unique nonce, a configurable expiry (default 10 minutes), and a QR-encodable payload.
- **FR21.** `POST /device/payment-request/{id}/pay` settles a `PENDING`, non-expired
  request as a balanced `QR_PAYMENT_ONLINE` posting, marks it `PAID`. Rejects: unknown
  request (404), already-paid or already-expired request (409), expired-at-check-time
  request (410, and marks it `EXPIRED`), self-payment (400), insufficient balance (422),
  missing idempotency key (400), duplicate idempotency key (returns original result, no
  double-post — same guarantee as FR19).
- **FR22.** A scheduled job (`payment-request-expiry`, ShedLock-guarded) marks `PENDING`
  payment requests past their `expiresAt` as `EXPIRED` at least once per minute, independent
  of the pay endpoint's own real-time expiry check.
- **FR23.** Offline transactions carry an `origin` field (`BLE` or `QR`), defaulting to
  `BLE`. Settlement logic, signature verification, and counter/replay checks are identical
  regardless of origin — `origin` is informational only and never affects trust decisions.
- **FR24.** `transfer.online.max-amount-idr` and `qr-payment.request-ttl-minutes` are
  required, environment-configurable properties with documented defaults (Rp 10,000,000
  and 10 minutes respectively). No hardcoded fallback exists in code — a missing value
  fails application startup.

---

## 7. Technical constraints

- Hostinger KVM2: 2 vCPU, 8 GB RAM, Ubuntu 24.04.
- Backend: Java 21 / Spring Boot 3.x / PostgreSQL 16 / Mosquitto / Caddy / Docker Compose.
- Backoffice: Next.js 16 / Bun / shadcn/ui.
- Landing page: Next.js 16, SEO-first, public article API.
- ESP32 firmware: C/C++, BLE + Ed25519 on-device (firmware team, separately scoped —
  Bayar QR camera/scan/render work included).
- CI/CD: GitHub Actions → GHCR → VPS deploy via SSH on push to `main`, one pipeline per repo.
- **New (Phase 2):** online endpoints add no new infrastructure — they run in the existing
  `api` container, synchronous, no new worker responsibilities except the payment-request
  expiry job.

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

**Phase 2 (new):**
8. A user can transfer online directly to another user, and the receiving user's balance
   reflects it immediately, without any BLE or pouch involvement.
9. A duplicate submission of the same online transfer (simulating a network retry) never
   results in the money moving twice — demonstrable by submitting the identical request
   with the same `Idempotency-Key` twice in a row.
10. A Bayar QR Online payment request that is paid once cannot be paid again — a second
    payment attempt against the same request is rejected.
11. A Bayar QR Online payment request past its expiry is rejected even if the client submits
    a technically well-formed payment attempt.
12. A Bayar QR Offline transaction settles through the identical verification path as a
    manually-initiated BLE transfer — demonstrable by comparing settlement behavior for
    `origin=BLE` vs `origin=QR` transactions with otherwise identical signed payloads.

---

## 9. Decisions & risks

### Phase 1 (resolved, kept for reference)
- R1–R7, Q1–Q4, §9a/§9b as previously documented — unchanged, still in force. Max pouch
  Rp 3,000,000, 24h certificate validity, no multi-hop offline re-spend, etc.

### Phase 2 — NEW

- **R8: Idempotency key ownership. DECIDED — device-generated.** The device, not the
  server, generates the `Idempotency-Key` for online transfer and Bayar QR payment
  requests. Rationale: only the device knows whether a given request is a genuine retry of
  an ambiguous prior attempt (e.g. after a timeout) or an intentionally new transaction.

- **R9: Self-transfer. DECIDED — always rejected.** Both `/device/transfer` and paying
  one's own `/device/payment-request` are rejected with 400. No legitimate use case for
  a user moving money to themselves via these endpoints exists at this stage.

- **R10: Online transfer maximum amount. DECIDED — Rp 10,000,000, configurable.** Chosen
  as a round, generous ceiling — well above expected typical transaction sizes, while still
  bounding the damage of any single error, bug, or abuse case. Unlike the offline pouch
  limit (which exists because a lost/compromised device is unrecoverable), this limit exists
  purely as a sanity ceiling, since online transfers are always server-verified in real time
  with no offline trust window. **Must be revisited before any real-money deployment** — no
  documented business rationale beyond "a safe round number," same caveat as the original
  pouch limit decision.

- **R11: Bayar QR Offline reuses the existing BLE trust model.** No new cryptographic
  design was needed — the QR is purely a data-entry shortcut. This was confirmed explicitly
  with the client to avoid the more complex (and initially considered) alternative of
  encoding a fully signed transaction into the QR itself, which would have required solving
  QR data-capacity constraints and a different security model with no live BLE handshake to
  anchor trust to.

- **R12: Naming — "Bayar QR" not "QRIS".** Explicit client decision. QRIS is a registered
  Bank Indonesia standard; using the name for a non-interoperable in-ecosystem feature risks
  user confusion and potential regulatory scrutiny if the product scales. See NG9.

---

## 10. Milestones

### Phase 1 — Complete
All PRs (scaffold through admin read endpoints, real auth, articles, backoffice, landing
page, infrastructure) merged, deployed, and verified in production as of Phase 1 close.

### Phase 2 — Online Transactions & Bayar QR (current)

| # | Task | Status |
|---|------|--------|
| 1 | RAB & Proposal drafted, reviewed | ✅ done |
| 2 | RAB & Proposal approved by Faisal | ✅ done |
| 3 | Invoice issued | ✅ done |
| 4 | CLAUDE.md / PRD.md updated for Phase 2 scope | ✅ done (this revision) |
| 5 | Transfer Online (FR18, FR19) | ⬅ next |
| 6 | Bayar QR Online (FR20–FR22) | pending |
| 7 | Bayar QR Offline — backend portion (FR23) | pending |
| 8 | Device simulator updated for new flows | pending |
| 9 | End-to-end testing across all Phase 2 features | pending |
| 10 | Documentation updates (README, MQTT contract, API examples) | pending |
| 11 | Production deployment & verification | pending |
| 12 | Payment received (Bukti Pembayaran finalized) | pending |

Build order follows the RAB week-by-week plan: Transfer Online (week 1) → Bayar QR Online
(week 2) → Bayar QR Offline backend (week 3) → cross-cutting work (week 4).