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

Digital payments in Indonesia assume connectivity. In areas with weak or no internet — rural
markets, transport, events, network outages — cashless payment breaks down. Dompet Digital is a
small hardware wallet that lets two people transfer value **device-to-device over Bluetooth with no
internet**, settling with the server later when connectivity returns.

This document defines what the **prototype** must do. It is deliberately narrow. Anything not listed
in §4 is out of scope (§5) for this phase.

---

## 2. Goals

- **G1.** Demonstrate a complete offline value transfer between two devices over BLE, with both devices authenticated, and have it correctly settle on the server after reconnect.
- **G2.** Prevent offline double-spending within the limits of the prototype (cap exposure via the offline-pouch model).
- **G3.** Prove the backend architecture (API + worker separation, transactional inbox, double-entry ledger) works end to end.
- **G4.** Be demonstrable on a single Hostinger KVM2 server.

## 2a. Non-goals (explicit — protects the timeline)

- **NG1.** Not production-grade security certification, PCI/regulatory compliance, or a real banking integration. (Transport auth is prototype-grade — see CLAUDE.md §4.)
- **NG2.** Not real money or a real payment-network (QRIS settlement) connection. Balances are prototype tokens denominated in IDR.
- **NG3.** No multi-hop offline chains beyond what §4 allows (received-offline funds re-spent offline before sync) unless explicitly added later.
- **NG4.** No iOS/Android consumer app beyond what's needed to demo; no merchant dashboard beyond a minimal admin view.
- **NG5.** No high-availability, horizontal scaling, or multi-region. One box.
- **NG6.** No KYC, onboarding flows, dispute resolution, or customer support tooling.
- **NG7.** Balance enquiry returns **current figures only** — no transaction history, statements, or exportable account records in the prototype.

> If a feature is not in §4, assume it is a non-goal for the prototype and raise it before building.

---

## 3. Users

- **Device holder (payer/payee).** Owns a Dompet device, **checks their balance (Cek Saldo)**, tops up online, transacts offline. Interacts via the device's screen + buttons and a PIN.
- **Admin (team/operator).** Registers devices, tops up balances, and views devices, balances, sync status, and flagged transactions via read-only endpoints (and, optionally, a minimal UI).

---

## 4. In-scope features (the prototype build list)

> **The bigger picture — the device's three core user actions mirror its on-screen menu:**
> **Cek Saldo** (check balance, §4.2a), **Transfer** (offline device-to-device over BLE, §4.4),
> and **Scan QR** (QRIS-style request, §4.5). Everything else in §4 (registration, top-up, pouch,
> sync, reconciliation, admin) is the supporting machinery that makes those three trustworthy.

### 4.1 Device registration & identity
- Each device generates an Ed25519 keypair on first setup; the private key never leaves the device.
- Registration is **admin-initiated**: the admin registers a device against a user, supplying the device's **public key**. The server returns a device API token once (see CLAUDE.md §4).
- Backend can mark a device active/suspended/locked.

### 4.2 Online top-up
- Admin adds balance to a user's **online wallet** (prototype: a simple credit action; no real payment rail — see NG2).
- Recorded as a double-entry ledger transaction.

### 4.2a Balance enquiry — "Cek Saldo" (standalone read)
- A user/device can check their balance **without performing any transaction**. This is the device's
  first menu item ("Cek Saldo") and a first-class feature in its own right — not merely the
  pre-transaction guard described in FR3a.
- **Online (device connected):** the device queries the API and receives the **authoritative online
  balance** (derived from the ledger), plus the amount **currently committed to its offline pouch**
  (the active certificate's issued amount, as the server sees it). The user sees their full position:
  spendable-online and held-in-pouch.
- **Offline (no connection):** the device displays its **local pouch balance** — the figure it
  maintains and decrements as it spends offline. This is firmware-side; there is no server call.
- **Freshness caveat (important):** the server's pouch figure reflects what was *loaded*, not what
  has been *spent offline since the last sync* — the server only learns of offline spends at sync.
  So while offline, the device's local number is the accurate spendable figure; while online, the
  server's online balance is authoritative. The two reconcile at sync.
- **Scope:** current figures only (see NG7). No history or statement.
- **No schema change:** this reads existing ledger and certificate tables; nothing new is stored.

### 4.3 Offline pouch provisioning
- While online, a device requests to load an amount into its offline pouch (up to the configured max).
- Backend **debits the online balance immediately** and issues a **signed certificate** (device id, issued amount, 24h expiry) the device stores locally.
- The device can now spend up to the issued amount offline.

### 4.4 Offline transfer over BLE (the headline feature)
- Two devices discover each other over BLE and **mutually authenticate** (exchange certificates + challenge-response proving each holds its private key).
- Both users confirm with a local PIN.
- **Balance & certificate check (pre-transaction):** the sender's device first verifies that its **local pouch balance** covers the amount and that its certificate is still valid (not expired/revoked). If funds are insufficient or the certificate is invalid, the transaction is refused on-device before anything is signed. Offline, this is a *local* check — there is no server to ask. This is a UX guard, not the security control (see FR3a). It reuses the same local pouch figure surfaced by Cek Saldo (§4.2a).
- Sender constructs and signs a transaction `{txn_id, sender, receiver, amount, counter, timestamp}`; receiver verifies and countersigns.
- Both devices update local pouch balances and append to a local **append-only log**.
- A per-device **monotonic counter** prevents replay.

### 4.5 Offline QRIS-style request (lightweight)
- A receiver can display a QR encoding a payment request (amount + receiver device id + nonce). The actual value transfer still happens over the signed BLE exchange.
- Before the payer commits, the payer's device performs the same balance & certificate check as 4.4 (local pouch balance when offline; online balance when online).
- **Prototype scope:** QRIS-format compatibility is cosmetic; this is not a real QRIS settlement.

### 4.6 Sync & settlement
- On reconnect, the device uploads its signed transaction batch over HTTPS.
- API validates auth, stores the raw batch in `sync_inbox`, returns `202`.
- Worker validates signatures + counters, checks pouch limits, posts balanced ledger entries, and flags anything inconsistent.
- Device is notified of the result over MQTT.

### 4.7 Reconciliation job
- A scheduled worker job verifies, per certificate: `issued − signed_outflows == reported_pouch_balance` (received funds settle to the receiver's online account, not back into a pouch — see §9a).
- Mismatches are written to a flagged table with a reason. No silent drops.

### 4.8 Admin read endpoints (minimal)
- Read-only API endpoints listing devices, online balances, pouch certificates, recent syncs, and flagged transactions. **The backend endpoints are in scope; a UI on top of them is optional and deferred** (NG4) — a minimal page or even an API-client view is sufficient for the demo.

---

## 5. Out of scope (restated for emphasis)

See §2a. In short: no real money, no real QRIS/bank rail, no compliance, no scaling, no consumer app polish, no multi-hop offline re-spend, no transaction history/statements (balance enquiry is current-figures-only). Build §4 and nothing beyond it.

---

## 6. Functional requirements (numbered, testable)

- **FR1.** A device can be registered with a public key and issued an identity + device token. Duplicate registration is rejected, and a user may hold at most **3** devices.
- **FR2.** Top-up creates a balanced ledger transaction; online balance reflects it.
- **FR3.** Pouch provisioning debits online balance atomically and returns a signed certificate; if online balance < requested, it is rejected.
- **FR3a.** Before a transfer or QRIS transaction, the device checks available balance and certificate validity (local pouch balance when offline; online balance when online) and refuses the transaction if funds are insufficient or the certificate is expired/revoked. This is a UX / early-fail guard only — integrity is still enforced at settlement via signatures, the `UNIQUE(sender_device_id, counter)` constraint, and pouch-limit checks. A balance check is never trusted as the anti-double-spend mechanism.
- **FR4.** An offline transaction with a valid sender signature and counter strictly greater than the last seen counter for that device is accepted; a replayed `(sender_device_id, counter)` is rejected by the database.
- **FR5.** Sync ingest responds `202` within ~200 ms typical and performs no ledger writes.
- **FR6.** The worker settles a valid batch into the ledger exactly once; re-uploading the same batch produces no duplicate entries.
- **FR7.** A batch whose outflows exceed the pouch certificate is flagged, not posted.
- **FR8.** A malformed batch fails its inbox row with a reason and does not crash or block the worker on other batches.
- **FR9.** Reconciliation flags any certificate whose arithmetic doesn't reconcile.
- **FR10.** Admin read endpoints surface devices, balances, certificates, syncs, and flagged transactions.
- **FR11.** At sync, the unspent portion of a settled certificate is refunded to the user's online balance and the certificate is closed (`SETTLED`). (Q3)
- **FR12.** A batch uploaded after its certificate's 24h expiry is still settled but flagged `EXPIRED_CERT_LATE_SYNC` and its inbox row marked `synced_after_expiry`. (Q2)
- **FR13.** A device has at most one `ACTIVE` certificate at a time; a new pouch-load is rejected until the prior certificate is settled/closed. (Q4)
- **FR14.** A user/device can retrieve its current balance **without any money movement**: the API returns the authoritative **online balance** (ledger-derived) and the amount **committed to the active pouch certificate**, clearly labelled. The endpoint is **read-only** — it never writes to the ledger. (The offline pouch figure shown on the device while disconnected is firmware-side and not a backend call.)

---

## 7. Technical constraints

- Single Hostinger KVM2: 2 vCPU, 8 GB RAM, Ubuntu 24.04. Memory budget is real — see CLAUDE.md.
- Stack: Java 21 / Spring Boot 3.x / PostgreSQL 16 / Mosquitto (MQTT) / Caddy / Docker Compose.
- Device firmware: ESP32 (C/C++, ESP-IDF or Arduino framework). BLE + Ed25519 on-device.
- Monitoring: Spring Boot Actuator + Uptime Kuma; optional Grafana Cloud agent. No self-hosted Prometheus on this box.
- Backups: nightly encrypted `pg_dump` via restic to Cloudflare R2 / Backblaze B2, plus Hostinger snapshots.

---

## 8. Success criteria for the prototype

1. Two physical (or simulated) devices complete an offline transfer with no internet, and it settles correctly on the server after reconnect.
2. A replayed/duplicated sync batch does **not** create duplicate balance — demonstrable on demand.
3. An over-limit or tampered batch is caught and flagged, not posted.
4. A user can check their balance (Cek Saldo) and the figures reconcile correctly across an offline-spend-then-sync cycle.
5. The full stack runs within the 8 GB box without swapping under demo load.
6. A backup can be **restored** successfully (tested at least once before the demo).

---

## 9. Key risks & decisions

- **R1 (critical path): firmware, not backend.** BLE + Ed25519 on ESP32 is the hardest, least-predictable work. **Mitigation:** build and test the entire backend against a **software device simulator** that speaks the same signed-payload + sync API, so backend progress does not wait on hardware.
- **R2: multi-hop offline re-spend. DECIDED — no.** Received-offline funds are not re-spendable offline. The receiver must go online and sync; received value settles into the **online** balance (not a new pouch). To spend offline again, the receiver does a fresh pouch-load.
- **R3: pouch limit & expiry. DECIDED.** Max **3 devices per user**; certificate validity **24 hours**.
- **R4: PIN/auth UX. ASSUMED.** Device-local PIN with lockout after a few failed attempts (device gains a `LOCKED` status). Firmware-track detail; confirm with hardware team.
- **R5: QRIS expectations. CONFIRMED.** §4.5 is cosmetic for the prototype — not a real QRIS settlement.
- **R6: clock trust offline. CONFIRMED.** Counters (not timestamps) are the integrity mechanism. This is why a device that syncs after certificate expiry has its batch flagged for review (FR12).
- **R7: balance ambiguity offline. CONFIRMED handling.** "Balance" has two views — the server's authoritative online figure and the device's local pouch figure — which diverge by whatever has been spent offline since the last sync. The Cek Saldo feature (§4.2a, FR14) surfaces both and labels them; offline, the device's local figure is the spendable truth.

### 9a. Settlement rules (decided — Q1–Q4)

- **Q1 — Received funds → online balance.** Offline transfer settlement debits the sender's pouch and credits the receiver's **online** account.
- **Q2 — Late sync.** If a device syncs *after* its certificate expired, signed transactions are still settled but the batch is **flagged for admin review** (`synced_after_expiry = true`, flag reason `EXPIRED_CERT_LATE_SYNC`). See FR12.
- **Q3 — Unspent pouch refund.** At sync, the unspent portion of a pouch is refunded to the online balance and the certificate is closed (`SETTLED`). See FR11.
- **Q4 — One active certificate per device.** A new pouch-load requires the previous certificate to be settled/closed. See FR13.

### 9b. Still open (does not block the backend build)

- **Max pouch amount (IDR).** A single configuration number, to be set by Faisal. Defaulted via config until provided; not a structural decision.

---

## 10. Milestones (see roadmap for detail)

1. Product definition frozen (this PRD approved). ✅
2. Data design: ERD + schema DDL + Flyway baseline. ✅
3. Environment: VPS hardened, Docker Compose (Postgres) up. ✅
4. API service built (Claude Code). ◀ current (scaffold + auth/device done)
5. Worker built (Claude Code).
6. Device simulator + firmware track (parallel).
7. Integration + double-spend/restore testing.
8. Hardening + demo.