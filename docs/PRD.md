# Product Requirements Document — Dompet Digital (Dompet Garuda)

| | |
|---|---|
| **Project** | Dompet Digital — offline-capable IoT payment device |
| **Initiator** | Faisal (via Fastwork) |
| **Stage** | Prototype / proof-of-concept |
| **Doc owner** | Backend team |
| **Status** | Phase 1 & Phase 2 delivered and verified in production. Phase 2b (MQTT provisioning) delivered. Device ID format change locked, migration pending. Phase 3 (transaction history, analytics, notification reconciliation) — revision v1.1 received from Faisal, scope/price mismatch identified, renegotiation pending before work starts. |

---

## 1. Problem & premise

Digital payments in Indonesia assume connectivity. Dompet Digital is a hardware wallet
that lets two people transfer value device-to-device over Bluetooth with no internet,
settling with the server later when connectivity returns.

---

## 2. Goals

**Phase 1 (delivered):** G1–G4 — complete offline transfer, prevent double-spending,
prove the API+worker+ledger architecture, run on a single Hostinger KVM2.

**Phase 2 (delivered):** G5–G7 — direct online transfer without BLE/pouch, QR-assisted
payments online and offline, preserve every money-safety invariant across the new
synchronous flows.

**Phase 3 (proposed, under negotiation):** enable transaction history, analytics, and
reliable (reconciled) notification delivery — see §10 for current status.

## 2a. Non-goals

- **NG1.** Not production-grade security or compliance.
- **NG2.** Not real money — prototype tokens in IDR.
- **NG3.** No multi-hop offline re-spend.
- **NG4.** No consumer mobile app.
- **NG5.** No horizontal scaling or multi-region.
- **NG6.** No KYC or dispute resolution.
- **NG7.** *(Historically: "balance enquiry returns current figures only, no history."
  This is the exact constraint Phase 3's Transaction History feature proposes to
  relax — see §10.)*
- **NG8.** No further writer/article features beyond what's delivered.
- **NG9. "Bayar QR" is NOT QRIS.** No Bank Indonesia / bank / PJP integration.
- **NG10.** No hardware/firmware work in backend-costed scope, except specifications
  handed to the firmware team (QR payload spec, BLE protocol draft, and now the device
  ID format below).

---

## 3. Users

- **Device holder** — owns a device, checks balance, tops up, transacts offline,
  online, and via Bayar QR.
- **Admin** — registers devices, tops up balances, manages users/flags via backoffice.
- **Writer** — manages articles via backoffice.

---

## 4. In-scope features

Phase 1 and Phase 2 features are unchanged from prior revisions of this document —
device registration, top-up, Cek Saldo, offline pouch, BLE transfer, sync/settlement,
reconciliation, admin reads, real auth, articles, Transfer Online, Bayar QR Online,
Bayar QR Offline. See the project's Obsidian knowledge base (`Phase 1 - Offline
Foundation`, `Phase 2 - Online Transactions and Bayar QR`) for the full narrative if
needed — this document focuses on what's new or changed.

### 4.9 Device ID format (NEW, August 2026)

`deviceId` changes from a server-generated UUID v4 to a **plain string identifier
sourced from the hardware/firmware team's own scheme** (expected to be derived from
the device's factory-assigned MAC address, though the backend does not need to know or
validate the semantic meaning of the string — only its format constraints).

**Locked constraints:**
- Column type: `VARCHAR(128)`.
- Must not contain `/` (breaks MQTT topic structure — `wallet/{deviceId}/#`) or `|`
  (breaks the offline signature message delimiter format).
- No other format assumption is made — the backend treats it as an opaque string,
  unique per device, exactly as it treated the UUID before.
- This changes **only** `deviceId`. `userId`, `certificateId`, `requestId`, and every
  other identifier in the system remain UUID and are unaffected.

This is a backend-side accommodation of a decision made by the hardware team, not a
product feature — no user-facing behavior changes.

---

## 5. Out of scope

Unchanged: real QRIS/bank/PJP integration, consumer mobile app, hardware procurement,
third-party security audits, large-scale load testing.

---

## 6. Functional requirements

> FR1–FR24 are Phase 1 and Phase 2 (delivered, unchanged — see prior revisions for full
> text). FR25–FR26 are the MQTT provisioning feature (delivered). FR27 is new.

- **FR25.** `POST /admin/devices` provisions MQTT credentials atomically with device
  registration; failure rolls back the whole registration (503). **Delivered, verified
  end-to-end in production** (register → connect → suspend → rejected → reinstate →
  connect again, all confirmed against the live broker).
- **FR26.** `PATCH /admin/devices/{deviceId}/status` revokes/reinstates MQTT access on
  suspend/reactivate, best-effort. **Delivered, verified in production.**
- **FR27 (NEW).** `POST /admin/devices` rejects device registration with `400` if the
  submitted `deviceId` contains `/` or `|`. The `devices.device_id` column and every
  foreign key referencing it is `VARCHAR(128)`, not `UUID`. No endpoint, request shape,
  or response shape changes as a result of this — only the underlying type and the new
  validation rule.

---

## 7. Technical constraints

Unchanged infrastructure (Hostinger KVM2, Java 21/Spring Boot 3.x/PostgreSQL 16/
Mosquitto/Caddy/Docker Compose, Next.js 16 backoffice/landing, GitHub Actions CI/CD).

**New:** the device ID migration (FR27) requires a dedicated Flyway migration altering
`devices.device_id` and every referencing foreign key from `UUID` to `VARCHAR(128)`.
This must ship as its own PR, reviewed independently of any feature work, given how
many tables reference this column.

---

## 8. Success criteria

Phase 1 and Phase 2 criteria (met, verified in production — see prior revisions).

**New, for FR27:**
13. A device registered with a non-UUID-shaped string `deviceId` (e.g., a 12-character
    MAC-derived hex string) completes the full offline BLE transfer flow, MQTT
    provisioning, and every online endpoint exactly as a UUID-shaped ID did before —
    demonstrable by running the existing end-to-end offline transfer scenario with a
    deliberately non-UUID device ID and confirming no step fails or behaves differently.
14. Attempting to register a device with `deviceId` containing `/` or `|` is rejected
    with `400`, not silently accepted and left to corrupt MQTT topics or signature
    parsing downstream.

---

## 9. Decisions & risks

Unchanged: R1–R17 (see prior revisions / the Obsidian `Key Decisions Log` note for the
full table).

- **R18 (NEW): Device ID format. DECIDED — plain string, `VARCHAR(128)`, sourced from
  hardware team, no `/` or `|` allowed.** Driven by a hardware team decision to use
  their own device-identity scheme (likely MAC-derived) rather than a
  backend-generated UUID v4. The two character prohibitions are not arbitrary — they
  are direct consequences of existing MQTT topic structure and the offline signature
  message format, both already live in production. `VARCHAR(128)` was chosen as a
  generous bound rather than the exact expected length, since tightening a column
  later is trivial and getting a hard cap wrong upfront is not.

---

## 10. Phase 3 — Status: Revision Received, Renegotiation Pending

Faisal returned a revision (v1.1) of the original Phase 3 proposal (Transaction
History, Analytics Dashboard, Notification Reconciliation), which has since been
**approved**. The three features themselves are unchanged in direction. v1.1 expanded
scope beyond the original estimate without changing the price cap (Rp 8,800,000) —
one specific item from that expanded scope (the staging/UAT environment requirement)
was subsequently **descoped by mutual agreement with Faisal**, deferred to a separate
future proposal. What remains locked in for this phase:

- Notification must include a **reconciliation mechanism** for devices offline at
  settlement time, not just a one-shot MQTT publish (expands Feature A).
- Transaction status must be a first-class, cross-system concept
  (`SUCCESS`/`PENDING`/`FAILED`/`REVERSED`), consistent across ledger, API, backoffice,
  and device (expands Feature B; `REVERSED` implies a reversal concept that doesn't
  exist anywhere in the system today).
- **Admin access to user transaction history must be audit-logged** — a new subsystem,
  not present in the original estimate (expands Feature B).
- Dashboard must add total Rupiah value, per-status counts, active user/device counts,
  and 7-day/30-day trend lines (expands Feature C well past the original three metrics).
- ~~A staging/UAT environment must exist before production releases~~ — **descoped by
  mutual agreement with Faisal (August 2026).** This requirement is deferred to a
  separate future proposal, not part of this phase. Deployment for Phase 3 continues
  under the existing discipline: backup immediately before any schema-altering
  deployment, rollback command ready before starting, verify directly against
  production afterward — the same process already used for every prior phase.
- Formal acceptance criteria and test cases per feature, agreed before work starts.
- IP ownership, milestone-based payment (5 milestones), and a 6-month bug warranty —
  business/legal terms, not engineering scope, but material to the agreement.

**This document intentionally does not yet reflect Phase 3 as locked scope** — per
standing agreement, `CLAUDE.md`/`PRD.md` are updated only after a RAB is approved.
Once the scope-vs-price question is resolved with Faisal (either by increasing the
budget/hours to match the expanded requirements, or by explicitly deferring specific
items — staging environment and full audit logging are the most likely candidates —
to a later phase), this section will be replaced with the locked Phase 3 scope,
mirroring how Phase 2 was documented once its RAB was approved.

---

## 11. Milestones

### Phase 1 — Complete
All PRs merged, deployed, verified in production.

### Phase 2 — Online Transactions & Bayar QR — Complete
All three features delivered, RAB paid, verified directly against production.

### Phase 2b — MQTT Per-Device Provisioning — Complete
Infrastructure migrated, `MqttAdminClient` delivered, full provision/revoke/reinstate
cycle verified end-to-end in production.

### Immediate next — Device ID Format Migration (this revision)

| # | Task | Status |
|---|------|--------|
| 1 | Format decided with hardware team (string, no `/` or `|`) | ✅ done |
| 2 | Column length locked at `VARCHAR(128)` | ✅ done |
| 3 | `CLAUDE.md` / `PRD.md` updated | ✅ done (this revision) |
| 4 | Flyway migration: `devices.device_id` + all FKs, `UUID` → `VARCHAR(128)` | ⬅ start here |
| 5 | Registration validation: reject `/` or `\|` in `deviceId` (FR27) | pending |
| 6 | Update `MqttAdminClient`, QR payload spec, BLE protocol draft for string IDs | pending |
| 7 | Full regression: offline BLE flow, MQTT provisioning, all online endpoints, with a non-UUID test device ID | pending |
| 8 | Deploy & verify in production | pending |

### Phase 3 — Transaction History, Analytics, Notification Reconciliation

| # | Task | Status |
|---|------|--------|
| 1 | Original proposal (v1.0) sent | ✅ done |
| 2 | Revision (v1.1) received from Faisal | ✅ done |
| 3 | Scope-vs-price mismatch identified and flagged | ✅ done |
| 4 | v1.1 approved as-is by Rizki | ✅ done |
| 5 | Staging/UAT requirement descoped by mutual agreement, deferred to a future proposal | ✅ done (this revision) |
| 6 | `CLAUDE.md`/`PRD.md` updated for locked Phase 3 scope | ✅ done (this revision) |
| 7 | Feature A — Notification + reconciliation | pending |
| 8 | Feature B — Transaction history + status + audit log | pending |
| 9 | Feature C — Analytics dashboard | pending |

### Standing follow-up items (not yet scheduled, tracked for visibility)

- Backup restore test — never actually executed, flagged since Phase 1's original
  success criteria.
- `PATCH /admin/auth/password` — password change endpoint, not yet built.
- MQTT account passwords — all currently share one weak password, rotation pending.
- BLE GATT protocol for certificate exchange — still a draft, not finalized with the
  firmware team. **Now more time-sensitive**, since the hardware team is actively
  making device-identity decisions and should receive the finalized GATT spec in the
  same working conversation as this device ID change.
- Server public key embedding + rotation plan for firmware.