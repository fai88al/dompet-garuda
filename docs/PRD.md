# Product Requirements Document — Dompet Digital (Prototype)

| | |
|---|---|
| **Project** | Dompet Digital — offline-capable IoT payment device |
| **Initiator** | Faisal (via Fastwork) |
| **Stage** | Prototype / proof-of-concept |
| **Doc owner** | Backend team |
| **Status** | Approved for build |

---

## 1. Problem & premise

Digital payments in Indonesia assume connectivity. Dompet Digital is a hardware wallet that lets
two people transfer value device-to-device over Bluetooth with no internet, settling with the
server later when connectivity returns.

This document defines what the **prototype** must do. Anything not listed in §4 is out of scope.

---

## 2. Goals

- **G1.** Demonstrate a complete offline value transfer between two devices over BLE, correctly settled after reconnect.
- **G2.** Prevent offline double-spending (offline-pouch model).
- **G3.** Prove the backend architecture (API + worker, transactional inbox, double-entry ledger) end to end.
- **G4.** Be demonstrable on a single Hostinger KVM2 server.

## 2a. Non-goals

- **NG1.** Not production-grade security or compliance.
- **NG2.** Not real money or a real payment network. Balances are prototype tokens in IDR.
- **NG3.** No multi-hop offline re-spend.
- **NG4.** No consumer app; admin view only.
- **NG5.** No horizontal scaling or multi-region.
- **NG6.** No KYC or dispute resolution.
- **NG7.** Balance enquiry returns current figures only — no history or statements.

---

## 3. Users

- **Device holder.** Owns a Dompet device, checks balance, tops up, transacts offline.
- **Admin.** Registers devices, tops up balances, views the read-only dashboard.

---

## 4. In-scope features

> **The device's three core user actions:** **Cek Saldo** (§4.2a), **Transfer** (§4.4), **Scan QR** (§4.5). Everything else is the machinery that makes those trustworthy.

### 4.1 Device registration & identity
- Ed25519 keypair generated on device; private key never leaves the device.
- Admin-initiated registration. Server returns device token once; stores only hash.
- Backend can mark device active/suspended/locked.

### 4.2 Online top-up
- Admin credits a user's online wallet (no real payment rail — NG2).
- Recorded as a balanced double-entry ledger transaction (`TOPUP`).

### 4.2a Balance enquiry — "Cek Saldo" (standalone read feature)
- User checks balance without performing any transaction. First menu item on the device.
- **Online:** API returns authoritative online balance (ledger-derived) + pouch committed
  (active certificate `issued_amount`). Clearly labelled separately.
- **Offline:** device shows local pouch balance (firmware-side, no server call).
- **Freshness caveat:** server's pouch figure reflects loaded amount, not offline spends since
  last sync. Device's local figure is the accurate spendable number when offline.
- **Scope:** current figures only (NG7). No transaction history.
- **No schema change.** Reads existing ledger and certificate tables. Zero ledger writes.

### 4.3 Offline pouch provisioning
- Device loads up to `pouch_limit` (max **Rp 3,000,000** — set by Faisal for the prototype;
  must be reviewed before any real-money deployment) into offline pouch.
- Backend debits online balance immediately; issues signed certificate (24h expiry).
- Device can now spend up to the issued amount offline.

### 4.4 Offline transfer over BLE
- Mutual auth: exchange certificates + challenge-response over BLE.
- Both users confirm with local PIN.
- **Pre-transaction check:** sender's device checks local pouch balance and cert validity.
  If insufficient or expired: refuse locally. This is a UX guard, not the security control.
- Sender signs `{txn_id, sender, receiver, amount, counter, timestamp}`; receiver countersigns.
- Both devices update local pouch balance and append to local append-only log.
- Monotonic counter prevents replay.

### 4.5 Offline QRIS-style request
- Receiver displays QR with payment request (device id, amount, nonce). Payer scans.
- Same balance + cert check as §4.4 before payer commits.
- Actual value transfer via same BLE signing flow as §4.4.
- **Prototype scope:** QRIS-format compatibility is cosmetic; not real QRIS settlement.

### 4.6 Sync & settlement
- Device reconnects, uploads signed batch via HTTPS.
- API validates auth, stores raw batch in `sync_inbox`, returns `202`. No ledger writes.
- Worker validates signatures + counters, checks pouch limits, posts balanced entries,
  flags inconsistencies, publishes result via MQTT.

### 4.7 Reconciliation job
- Scheduled worker job: per certificate, verify `issued − signed_outflows == reported_pouch_balance`.
- Mismatches go to `flagged_transactions` with reason. Nothing silently dropped.

### 4.8 Admin read endpoints
- Read-only endpoints: devices, balances, certs, syncs, flagged transactions.
- UI is optional and deferred. Backend endpoints are in scope.

---

## 5. Out of scope

No real money, no real QRIS/bank rail, no compliance, no scaling, no consumer app, no multi-hop
offline re-spend, no transaction history. Build §4 only.

---

## 6. Functional requirements

> **FR numbering follows this PRD, not the README.** If the README shows different numbers,
> the README is wrong and should be corrected to match this document.

- **FR1.** Device registration with public key; device token issued once. Duplicate rejected.
  Max 3 devices per user.
- **FR2.** Top-up creates balanced ledger transaction; balance reflects it.
- **FR3.** Pouch provisioning debits online balance atomically; returns signed cert.
  Rejects if online balance < requested.
- **FR3a.** Pre-transaction balance + cert check (UX guard). Never trusted as anti-double-spend.
- **FR4.** Offline txn with valid signature + counter > `last_counter` accepted.
  Replayed `(sender_device_id, counter)` rejected by DB constraint.
- **FR5.** Sync ingest returns `202` within ~200 ms; no ledger writes.
- **FR6.** Worker settles valid batch exactly once; duplicate upload creates no duplicate entries.
- **FR7.** Over-limit batch flagged, not posted.
- **FR8.** Malformed batch fails with reason; does not crash or block worker.
- **FR9.** Reconciliation flags any certificate whose arithmetic doesn't reconcile.
- **FR10.** Admin read endpoints surface devices, balances, certs, syncs, flagged transactions.
- **FR11.** At sync, unspent pouch portion refunded to online balance; cert closed `SETTLED`.
- **FR12.** Batch synced after cert expiry is settled but flagged `EXPIRED_CERT_LATE_SYNC`.
- **FR13.** At most one `ACTIVE` cert per device; new pouch-load rejected if one exists.
- **FR14.** Balance enquiry returns online balance + pouch committed without any ledger writes.

---

## 7. Technical constraints

- Hostinger KVM2: 2 vCPU, 8 GB RAM, Ubuntu 24.04.
- Stack: Java 21 / Spring Boot 3.x / PostgreSQL 16 / Mosquitto / Caddy / Docker Compose.
- ESP32 firmware: C/C++, BLE + Ed25519 on-device.
- Monitoring: Spring Boot Actuator + Uptime Kuma; optional Grafana Cloud agent.
- Backups: nightly restic → Cloudflare R2 / Backblaze B2 + Hostinger snapshots.
- CI/CD: GitHub Actions → GHCR → VPS deploy via SSH on push to `main`.

---

## 8. Success criteria

1. Two devices complete an offline transfer with no internet; settles correctly after reconnect.
2. Replayed batch creates no duplicate balance — demonstrable on demand.
3. Over-limit or tampered batch caught and flagged, not posted.
4. Cek Saldo figures reconcile correctly across offline-spend-then-sync cycle.
5. Full stack runs within 8 GB without swapping under demo load.
6. Backup can be restored (tested at least once before demo).

---

## 9. Decisions & risks

- **R1 (critical path):** firmware, not backend. Mitigate with device simulator from PR6 onward.
- **R2: multi-hop re-spend. DECIDED — no.** Received offline funds → online balance at sync.
- **R3: pouch limit & expiry. DECIDED.** Max 3 devices per user; cert validity 24 hours.
- **R4: PIN/auth UX.** Device-local lockout; confirm with hardware team.
- **R5: QRIS. CONFIRMED** cosmetic for prototype.
- **R6: clock trust. CONFIRMED.** Counters (not timestamps) are the integrity mechanism.
- **R7: balance ambiguity offline. CONFIRMED.** Two views exist (server online + device local pouch);
  Cek Saldo labels both and explains the difference.

### 9a. Settlement rules (Q1–Q4)

- **Q1:** Offline transfer → DEBIT sender.pouch, CREDIT receiver.ONLINE (never a pouch).
- **Q2:** Late sync (post-expiry) → settle + flag `EXPIRED_CERT_LATE_SYNC`.
- **Q3:** Unspent at sync → `POUCH_REFUND` to online balance; cert → `SETTLED`.
- **Q4:** One active cert per device; new load rejected until prior is settled.

### 9b. Max pouch amount (IDR)

**DECIDED: Rp 3,000,000** (confirmed by Faisal).

Implement as `pouch.max-amount-idr=3000000` in `application.yml` — a required config property
with no hardcoded fallback. A missing value must cause a startup failure, not silently default
to a wrong number in a future real-money deployment.

⚠️ This was decided casually for the prototype with no documented rationale. With 3 devices
per user and 24h cert validity, maximum offline exposure per user is Rp 9,000,000. Must be
revisited with a proper risk review before any real-money deployment. PR5 is now unblocked.

---

## 10. Milestones

| # | Task | Status |
|---|------|--------|
| 1 | Product definition frozen | ✅ done |
| 2 | ERD + schema DDL + Flyway baseline | ✅ done |
| 3 | VPS hardened, Docker Compose up, CI/CD working | ✅ done |
| 4a | PR1 — Scaffold (profiles, Flyway, Testcontainers smoke test) | ✅ merged |
| 4b | PR2 — Auth + device registration (FR1) | ✅ merged |
| 4c | PR3 — Ledger core (double-entry posting, balance derivation) | ✅ merged |
| 4d | PR4 — Online top-up (FR2) | ✅ merged |
| 4e | PR4b — Balance enquiry / Cek Saldo (FR14) | ⬅ current |
| 4f | PR5 — Pouch provisioning + certificate (FR3, FR13) | ⬜ pending |
| 4g | PR6 — Sync ingest endpoint (FR5) | pending |
| 5a | PR7 — Worker bootstrap + inbox poller | pending |
| 5b | PR8 — Settlement (FR4, FR6–FR8, FR11, FR12) | pending |
| 5c | PR9 — Reconciliation job (FR9) | pending |
| 5d | PR10 — MQTT publisher | pending |
| 5e | PR11 — Admin read endpoints (FR10) | pending |
| 6 | Device simulator (parallel from PR6) | pending |
| 7 | Caddy + domain setup (after PR6 stable) | pending |
| 8 | Integration + safety testing | pending |
| 9 | Hardening + demo | pending |