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
| `wallet/{deviceId}/payment-received` | broker → device | 1 | no | api, immediately after an `ONLINE_TRANSFER` or `QR_PAYMENT_ONLINE` credits the receiver's online balance |

## Authorization

Each device may only publish/subscribe under `wallet/{itsOwnDeviceId}/#`, enforced by the
Mosquitto Dynamic Security plugin (CLAUDE.md §15) — not a static `acl` file. Every device is
attached to the single shared `device-role` role (never a per-device role).

## Per-device authentication (FR25/FR26)

- **Username:** the device's `deviceId` (UUID).
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
| `MqttAdminClient` | `api` | `dompet-api-admin` (`admin` role) | Provisions/revokes/reinstates per-device MQTT credentials only — never publishes to `wallet/#` topics |

> Note: `MqttPublisherService` is currently `@Profile("worker")` only, so the online-flow
> callers (`TransferController`, `PaymentRequestController`, `PouchController`) that inject it
> receive `null` in the api profile today and skip the publish (see the `// Null in the api
> profile` comments at each call site). This predates FR25/FR26 and is outside this PR's
> scope — flagged here only so it isn't mistaken for new behavior introduced by
> `MqttAdminClient`, which is a fully separate bean/connection.
