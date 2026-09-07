# MQTT Topic Contract

> Reference for CLAUDE.md §8. Notification transport only — MQTT never carries financial
> authority (§7 invariant 8). The ledger is always the source of truth for balances and
> settlement outcomes.

## Topics

| Topic | Direction | QoS | Retained | Published by |
|---|---|---|---|---|
| `wallet/{deviceId}/status` | device → broker | 1 | yes | device (Last-Will = `offline`) |
| `wallet/{deviceId}/sync-result` | broker → device | 1 | no | worker, after offline settlement (notifies only the uploading device — see CLAUDE.md §16.4 for the known gap on the receiving device) |
| `wallet/{deviceId}/cert-refresh` | broker → device | 1 | no | worker, after a pouch load commits |
| `wallet/{deviceId}/payment-received` | broker → device | 1 | no | api, immediately after an `ONLINE_TRANSFER` or `QR_PAYMENT_ONLINE` credits the receiver's online balance; worker, immediately after an `OFFLINE_TRANSFER` settles (Phase 3 Feature A, see below); api again, re-published on any authenticated device hit while a delivery attempt is still `PENDING` |

## Authorization

Each device may only publish/subscribe under `wallet/{itsOwnDeviceId}/#`, enforced by the
Mosquitto Dynamic Security plugin (CLAUDE.md §15) — not a static `acl` file. Every device is
attached to the single shared `device-role` role (never a per-device role).

## Per-device authentication (FR25/FR26)

- **Username:** the device's `deviceId` (plain string, hardware-sourced — not a UUID, CLAUDE.md §1a).
- **Password:** the same device API token issued once at `POST /admin/devices` — reused as-is
  for MQTT, not a separately generated secret.
- **Provisioning:** fully automatic as of this revision. `POST /admin/devices` provisions the
  device's MQTT credentials (`createClient` + `addClientRole` against `device-role`) as a
  mandatory part of registration — no manual broker-side setup is required per device. A
  provisioning failure rolls back the entire device registration (503).
- **Suspend/reinstate:** `PATCH /admin/devices/{deviceId}/status` revokes MQTT access
  (`disableClient`) when status moves to `SUSPENDED`/`LOCKED`, and restores it
  (`enableClient`) when status returns to `ACTIVE`. This is best-effort — an MQTT-side failure
  here never blocks or changes the status endpoint's response (FR26/R14).
- Implemented by `MqttAdminClient` (`com.dompetgaruda.api.mqtt`, `@Profile("api")` only),
  a connection fully separate from the worker's Paho publisher bean. It issues commands via
  Mosquitto's Dynamic Security control API (`$CONTROL/dynamic-security/v1` /
  `$CONTROL/dynamic-security/v1/response`), connected as the `dompet-api-admin` account.

## Transport

TLS on port 8883 only. Plain port 1883 is bound to `127.0.0.1` and used solely for local
`mosquitto_ctrl` administration inside the container/VPS — never exposed externally.

## Worker vs. api MQTT connections

Two entirely separate Paho connections exist, never merged:

| Bean | Profile | Account | Purpose |
|---|---|---|---|
| `MqttClient` (`MqttConfig`) / `MqttPublisherService` | `worker` | `dompet-worker` (`worker-role`) | Publishes `sync-result` / `cert-refresh` (and `payment-received` — see note below) |
| `MqttAdminClient` | `api` | `dompet-api-admin` (`admin` role) | Provisions/revokes/reinstates per-device MQTT credentials; as of Phase 3 Feature A, also re-publishes `payment-received` on `wallet/#` from the api profile (see below) |

> Note: `MqttPublisherService` is currently `@Profile("worker")` only, so the online-flow
> callers (`TransferController`, `PaymentRequestController`, `PouchController`) that inject it
> receive `null` in the api profile today and skip the publish (see the `// Null in the api
> profile` comments at each call site). This predates FR25/FR26 and is outside this PR's
> scope — flagged here only so it isn't mistaken for new behavior introduced by
> `MqttAdminClient`, which is otherwise a fully separate bean/connection.

## Phase 3 Feature A — Notification Reconciliation for offline transfers (CLAUDE.md §17)

`notification_log` tracks `payment-received` delivery for `OFFLINE_TRANSFER` settlements only
(never `ONLINE_TRANSFER`/`QR_PAYMENT_ONLINE` — those publish inline and untracked, unchanged).
Delivery metadata only — status here never gates or reflects money correctness (§7 invariant 8);
`GET /device/balance` never references this table.

1. Worker settlement (`SyncSettlementService`) inserts a `PENDING` row right after each
   `OFFLINE_TRANSFER` commits, then attempts an immediate publish via the real
   `MqttPublisherService` connection. Marked `DELIVERED` on confirmed publish.
2. Any authenticated hit on `POST /device/sync` or `POST /device/pouch/load` (a reconnect
   proxy, not a real presence signal — no new "I'm online now" endpoint was added) re-attempts
   every `PENDING` row for that device via `NotificationReconciliationService`. In the api
   profile this publishes through `MqttAdminClient` (see caveat below), since the worker's
   publisher bean doesn't exist there.
3. `NotificationExpiryJob` (worker, hourly, ShedLock) marks any `PENDING` row past
   `notification.reconciliation.expiry-days` (default 3, `NOTIFICATION_RECONCILIATION_EXPIRY_DAYS`)
   as `EXPIRED` — belt-and-suspenders for a receiver that never reconnects in time.

> [!warning] Unverified production assumption
> `MqttAdminClient.publishPaymentReceivedBestEffort` reuses the `dompet-api-admin` dynsec
> connection — which exists to send `$CONTROL/dynamic-security/v1` commands, not to publish on
> `wallet/#` — to re-publish notifications from the api profile. Whether the `admin` role's ACL
> on the **production** broker actually permits publishing there has not been confirmed. This
> is safe either way (best-effort, logged failure, never blocks the caller's request or touches
> the ledger), but if the ACL doesn't allow it, reconciliation-on-reconnect delivery is a no-op
> in production until a broker-side role change confirms/grants this — verify against the real
> broker's dynsec role config before relying on this for delivery timeliness. Test coverage
> (`NotificationBalanceCorrectnessTest`) exercises the "publish genuinely fails" path already,
> which is why this is safe to ship regardless: the money-safety invariant holds either way.
