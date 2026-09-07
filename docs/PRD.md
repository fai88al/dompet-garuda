# Product Requirements Document — Dompet Digital (Dompet Garuda)

| | |
|---|---|
| **Project** | Dompet Digital — offline-capable IoT payment device |
| **Initiator** | Faisal (via Fastwork) |
| **Stage** | Prototype |
| **Doc owner** | Backend team |
| **Status** | Phase 1, Phase 2, Phase 2b, and Device ID Migration all delivered. Phase 3 approved (v1.1) — Feature A (Notification Reconciliation) starting now. Online endpoint auth model changed to header-based device lookup (R19, see below) — deliberate decision, documented. |

---

## 1–9. Unchanged

Problem/premise, goals, non-goals, users, in-scope features (Phase 1/2), out of scope,
functional requirements FR1–FR27, R1–R18 — all unchanged from prior revisions. See the
Obsidian knowledge base or prior PRD revisions for full text.

---

## 6a. Functional requirements — new

- **FR28.** `POST /device/transfer`, `POST /device/payment-request`, and
  `POST /device/payment-request/{id}/pay` authenticate via a plain `device_id` /
  `Receiver-Device-Id` / `Payer-Device-Id` header — a direct lookup against `devices`,
  no Bearer token, no signature check. **Status: delivered.** See R19.

---

## 9a. Decisions — new

- **R19 (NEW): Online endpoint authentication. DECIDED — header-based device ID
  lookup, no Bearer token, no signature.** Raised explicitly as a security concern
  (deviceId is not secret by design — visible in QR payloads, MQTT topics, logs — so
  this means anyone knowing a valid deviceId can act as that device on these three
  endpoints). **Confirmed as the intended direction by the client-side decision-maker
  despite this trade-off being explained in full.** Documented here so it is never
  mistaken for an oversight or silently "fixed" back to token auth. Scope is limited
  to the three online synchronous endpoints — the offline BLE flow's Ed25519 signature
  verification (the system's actual cryptographic security control) is unaffected.

---

## 10. Milestones

### Delivered
Phase 1, Phase 2, Phase 2b (MQTT provisioning), Device ID Migration, Public Key format
validation (backend + backoffice), online-endpoint auth model change (R19) — all
delivered and live in production.

### Current — Phase 3, Feature A: Notification Reconciliation

| # | Task | Status |
|---|---|---|
| 1 | Proposal v1.0 sent, revised to v1.1, approved by Faisal | ✅ done |
| 2 | Acceptance criteria + test cases agreed (signed doc) | ✅ done |
| 3 | 3-day expiry window confirmed with client | ✅ done |
| 4 | `CLAUDE.md`/`PRD.md` updated for this milestone | ✅ done (this revision) |
| 5 | `notification_log` migration + worker settlement integration | ⬅ start here |
| 6 | Reconciliation-on-reconnect + scheduled expiry sweep | pending |
| 7 | All 7 test cases passing, verified in production | pending |
| 8 | Milestone payment (20%, Rp 1,600,000) invoiced | pending |

### Not yet started
Feature B (Transaction History), Feature C (Analytics Dashboard) — both depend on
Feature A completing first per the agreed build order.

### Standing follow-up (unscheduled)
Backup restore test, admin password-change endpoint, MQTT password rotation, BLE GATT
spec for firmware, server public-key rotation plan.