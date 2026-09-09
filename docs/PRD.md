# Product Requirements Document — Dompet Digital (Dompet Garuda)

| | |
|---|---|
| **Project** | Dompet Digital — offline-capable IoT payment device |
| **Initiator** | Faisal (via Fastwork) |
| **Stage** | Prototype |
| **Doc owner** | Backend team |
| **Status** | Phase 1, Phase 2, Phase 2b, Device ID Migration, Feature A, and Feature B all delivered and verified live. Feature C (Analytics Dashboard) starting now — last feature in the approved v1.1 scope, final handover follows once it closes. Device auth unified to a single `Device-Id` header across every device endpoint (R19 → expanded by R20) — deliberate decision, documented, consequences explicitly flagged and confirmed. |

---

## 1–9. Unchanged

Problem/premise, goals, non-goals, users, in-scope features (Phase 1/2), out of scope,
functional requirements FR1–FR27, R1–R18 — all unchanged from prior revisions. See the
Obsidian knowledge base or prior PRD revisions for full text.

---

## 6a. Functional requirements — new

- **FR28.** Every device-facing endpoint (`/device/sync`, `/device/pouch/load`,
  `/device/balance`, `/device/transfer`, `/device/payment-request*`) authenticates
  via a single `Device-Id` header — direct lookup against `devices`, no Bearer token,
  no signature check at the HTTP layer. **Status: delivered.** See R19, R20.

---

## 9a. Decisions — new

- **R19: Online endpoint authentication. DECIDED — header-based device ID lookup, no
  Bearer token, no signature.** Originally scoped to the three online synchronous
  endpoints only. Raised explicitly as a security concern (deviceId is not secret by
  design). **Confirmed as the intended direction by the client-side decision-maker
  despite this trade-off being explained in full.**

- **R20 (NEW): Expanded to ALL device endpoints, `Device-Token` concept dropped
  entirely. DECIDED — single unified `Device-Id` header everywhere, including
  `GET /device/balance` and `POST /device/pouch/load` (previously genuinely
  Bearer-token-verified).** This is a materially larger decision than R19 — it
  removes authentication from balance lookups (privacy exposure: anyone knowing a
  `deviceId` can check that device's balance) and from pouch loading (the mechanism
  that issues the certificate underlying the entire offline BLE trust model).
  **This consequence was explicitly flagged to the client-side decision-maker before
  implementation, and the decision was confirmed to proceed anyway.** The offline
  BLE flow's Ed25519 signature verification remains the actual cryptographic
  security control for settlement and is unaffected by this decision — R20 only
  affects who can call these HTTP endpoints. `DeviceTokenVerifier` is retained in
  code as dead code (not deleted) but wired into nothing; `DeviceTokenService`
  remains in use for MQTT password generation only.

---

## 10. Milestones

### Delivered
Phase 1, Phase 2, Phase 2b (MQTT provisioning), Device ID Migration, Public Key format
validation (backend + backoffice), online-endpoint auth model change (R19) — all
delivered and live in production.

### Feature A — Notification Reconciliation — CLOSED (with documented known limitation)

| # | Task | Status |
|---|---|---|
| 1–4 | Proposal, AC agreement, expiry window, docs | ✅ done |
| 5–6 | Implementation, tests | ✅ done, PR merged |
| 7 | Live production verification (real signed offline transfer, both scenarios) | ✅ done |
| — | **Known limitation found during live testing**: `DELIVERED` set on MQTT PUBACK, not actual device receipt — false positive when receiver isn't connected. Money-safety unaffected (verified). Accepted as tech debt (Option C), documented in CLAUDE.md §17, deferred to after B/C. | ✅ documented |
| 8 | Milestone payment (20%, Rp 1,600,000) | ready to invoice |

### Feature B — Transaction History — CLOSED

| # | Task | Status |
|---|---|---|
| 1 | `CLAUDE.md` §18 spec, both decisions resolved (Device-Id auth; REVERSED reserved-only) | ✅ done |
| 2 | Backend: endpoints + pagination + audit log (PR #38) | ✅ done, merged |
| 3 | Backoffice: transaction history section on user detail page (PR #14) | ✅ done, merged |
| 4 | Live production verification (both backend and backoffice) | ✅ done — confirmed by human developer live at backoffice.dompetgaruda.com |
| 5 | Milestone payment (25%, Rp 2,000,000) | ready to invoice |

### Current — Phase 3, Feature C: Analytics Dashboard

| # | Task | Status |
|---|---|---|
| 1 | `CLAUDE.md` §19 spec drafted | ✅ done (this revision) |
| 2 | Backend: `GET /admin/analytics/overview` + aggregation queries | ⬅ start here |
| 3 | Backoffice: dashboard charts (recharts, already in stack) | pending backend |
| 4 | Live production verification | pending |
| 5 | Milestone payment (25%, Rp 2,000,000) | pending |
| 6 | Final handover (10%, Rp 800,000) — after Feature C closes | pending |

### Not yet started
Nothing — Feature C is the last feature in the approved v1.1 scope. Final handover
follows once it closes.

### Standing follow-up (unscheduled)
Backup restore test, admin password-change endpoint, MQTT password rotation, BLE GATT
spec for firmware, server public-key rotation plan.