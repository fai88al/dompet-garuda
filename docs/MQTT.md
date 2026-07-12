# MQTT Contract — Dompet Digital

> Place at `docs/MQTT_CONTRACT.md`. This document is for the **ESP32 firmware developer**.
> It defines everything the device needs to know to connect to the broker and exchange
> messages with the backend. The backend is already built to this spec — the firmware
> must match it exactly.

---

## 1. Broker connection details

| Parameter | Value |
|---|---|
| Host | `mqtt.dompetgaruda.com` |
| Port | `8883` (TLS only — plain 1883 is never exposed publicly) |
| Protocol | MQTT v3.1.1 over TLS |
| TLS CA | Let's Encrypt ISRG Root X1 (already trusted by most OS/SDK trust stores) |
| Authentication | Username + password (required — anonymous connections are rejected) |

### Device credentials

Each device authenticates with:
- **Username:** the device's `device_id` (UUID, e.g. `cb6df991-f82f-4099-ba5c-b9940cf6459e`)
- **Password:** the device's API token (issued once at registration, provisioned onto the device)

The worker service account (`dompet-worker`) is a separate credential used only by the
backend. Device firmware must never use the worker credentials.

### TLS on ESP32 (Arduino framework)

The ISRG Root X1 CA cert is available at:
`https://letsencrypt.org/certs/isrgrootx1.pem`

```cpp
const char* mqtt_host = "mqtt.dompetgaruda.com";
const int   mqtt_port = 8883;

// Embed ISRG Root X1 in firmware
const char* ca_cert = \
"-----BEGIN CERTIFICATE-----\n" \
"MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw\n" \
"TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh\n" \
"cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4\n" \
"...(full cert)...\n" \
"-----END CERTIFICATE-----\n";

WiFiClientSecure espClient;
espClient.setCACert(ca_cert);

PubSubClient mqttClient(espClient);
mqttClient.setServer(mqtt_host, mqtt_port);
```

---

## 2. Topic structure

All topics follow this pattern:

```
wallet/{device_id}/{subtopic}
```

Where `{device_id}` is the UUID assigned at device registration.

**ACL rule:** each device can only publish and subscribe under its own
`wallet/{its_own_device_id}/#`. A device cannot read or publish to another device's topics.
The broker enforces this — violations are silently rejected.

---

## 3. Topics reference

### 3.1 `wallet/{deviceId}/status`

**Direction:** Device → Broker
**Publisher:** Device firmware
**QoS:** 1
**Retained:** YES

Device publishes its current status whenever it connects or its state changes.

**Payload:**
```json
{
  "status": "online",
  "batteryPercent": 85,
  "pouchBalance": 150000,
  "lastSyncAt": "2026-07-12T02:14:55Z"
}
```

| Field | Type | Description |
|---|---|---|
| `status` | string | `"online"` or `"offline"` |
| `batteryPercent` | int | 0–100 |
| `pouchBalance` | long | Current local pouch balance in whole Rupiah |
| `lastSyncAt` | string | ISO-8601 UTC timestamp of last successful sync |

**Last Will and Testament (LWT):**
Configure this at connection time so the broker automatically publishes an offline status
if the device disconnects unexpectedly:

```cpp
// Set LWT before connecting
String lwtTopic = "wallet/" + deviceId + "/status";
String lwtPayload = "{\"status\":\"offline\"}";
mqttClient.setWill(
    lwtTopic.c_str(),
    lwtPayload.c_str(),
    1,     // QoS 1
    true   // retained
);
```

---

### 3.2 `wallet/{deviceId}/sync-result`

**Direction:** Backend Worker → Device
**Publisher:** Backend worker (after settling a sync batch)
**QoS:** 1
**Retained:** NO

The backend publishes this after processing a sync batch uploaded by the device.
The device subscribes to this topic to know whether its offline transactions
were accepted, flagged, or failed.

**Payload:**
```json
{
  "batchId": "5af12de3-ff97-46d6-b3fe-7e1e8b1ebeb3",
  "status": "SETTLED",
  "detail": null
}
```

| Field | Type | Description |
|---|---|---|
| `batchId` | string | UUID of the sync_inbox batch this result refers to |
| `status` | string | `SETTLED`, `FLAGGED`, or `FAILED` |
| `detail` | string or null | Human-readable reason when status is FLAGGED or FAILED |

**Status meanings:**

| Status | Meaning | Device action |
|---|---|---|
| `SETTLED` | All transactions processed and posted to the ledger | Clear local log, update balance from server |
| `FLAGGED` | Transactions processed but one or more were anomalous | Show warning to user, contact admin |
| `FAILED` | Batch could not be processed (malformed payload etc.) | Retry upload or contact admin |

**Firmware example:**
```cpp
void mqttCallback(char* topic, byte* payload, unsigned int length) {
    String msg = String((char*)payload, length);
    // Parse JSON and check batchId matches the last uploaded batch
    // Update UI accordingly
}

// Subscribe after connecting
mqttClient.subscribe(("wallet/" + deviceId + "/sync-result").c_str(), 1);
```

---

### 3.3 `wallet/{deviceId}/cert-refresh`

**Direction:** Backend Worker → Device
**Publisher:** Backend worker (after pouch certificate is issued)
**QoS:** 1
**Retained:** NO

The backend publishes this after successfully issuing a new offline certificate for the
device. This is a hint — the device should pull the new certificate over HTTPS
(not over MQTT, because certificates are sensitive and MQTT is not the right transport
for them).

**Payload:**
```json
{
  "hint": "new certificate available"
}
```

**Device action:** on receiving this, call `GET /device/pouch/certificate` (or equivalent
HTTPS endpoint) to download the new certificate. Do not trust the MQTT payload itself
as proof of authorization — always fetch over HTTPS and verify the server signature.

---

## 4. Connection lifecycle

### Recommended connection flow (firmware)

```
1. WiFi connected
2. Set LWT on wallet/{deviceId}/status with payload {"status":"offline"}
3. Connect to mqtt.dompetgaruda.com:8883
   - Username: device_id
   - Password: device_token
   - Clean session: false (broker remembers subscriptions)
4. On connect success:
   a. Subscribe to wallet/{deviceId}/sync-result (QoS 1)
   b. Subscribe to wallet/{deviceId}/cert-refresh (QoS 1)
   c. Publish wallet/{deviceId}/status with {"status":"online",...} (QoS 1, retained)
5. Enter main loop — handle incoming messages, publish status updates as needed
6. On disconnect: broker auto-publishes LWT ({"status":"offline"})
```

### Reconnection

Configure auto-reconnect in your MQTT library. On reconnect, re-subscribe to all topics
since the broker may not retain subscriptions depending on clean session settings.

### Clean session: false

Setting `cleanSession = false` means the broker remembers the device's subscriptions
and queues QoS 1 messages delivered while the device was offline. When the device
reconnects, it receives any missed `sync-result` or `cert-refresh` messages.
This is important for reliability — a sync result published while the device is offline
will be delivered when it reconnects.

---

## 5. Message format rules

- All payloads are **JSON** encoded as **UTF-8**.
- Timestamps are **ISO-8601 UTC** (`2026-07-12T02:14:55Z`).
- Money amounts are **whole Rupiah as integers** — never decimals.
- The backend never publishes empty payloads.
- Topic strings use only lowercase, hyphens, and UUID characters — no special characters.

---

## 6. ACL summary

| Actor | Can publish to | Can subscribe to |
|---|---|---|
| Device A (`device_id_A`) | `wallet/device_id_A/#` | `wallet/device_id_A/#` |
| Device B (`device_id_B`) | `wallet/device_id_B/#` | `wallet/device_id_B/#` |
| Backend worker | `wallet/#` (any device) | `wallet/#` (any device) |

A device attempting to publish or subscribe to another device's topics will be silently
rejected by the broker. This is enforced server-side — the firmware does not need to
implement any additional access control.

---

## 7. Testing the connection

Before writing firmware, test the broker connection with `mosquitto_pub` / `mosquitto_sub`:

```bash
# Download Let's Encrypt CA cert
curl -o /tmp/isrg-root-x1.pem https://letsencrypt.org/certs/isrgrootx1.pem

# Subscribe to your device's sync-result (Terminal 1)
mosquitto_sub \
  -h mqtt.dompetgaruda.com \
  -p 8883 \
  --cafile /tmp/isrg-root-x1.pem \
  -u YOUR_DEVICE_ID \
  -P YOUR_DEVICE_TOKEN \
  -t "wallet/YOUR_DEVICE_ID/sync-result" -v

# Publish a test status (Terminal 2)
mosquitto_pub \
  -h mqtt.dompetgaruda.com \
  -p 8883 \
  --cafile /tmp/isrg-root-x1.pem \
  -u YOUR_DEVICE_ID \
  -P YOUR_DEVICE_TOKEN \
  -t "wallet/YOUR_DEVICE_ID/status" \
  -m '{"status":"online","batteryPercent":100,"pouchBalance":0}' \
  -r    # retained flag
```

If the subscribe command connects and the publish command sends without error,
the broker is reachable and credentials are correct.

---

## 8. Error codes and troubleshooting

| Error | Cause | Fix |
|---|---|---|
| Connection refused | Wrong port or host | Confirm host=`mqtt.dompetgaruda.com`, port=`8883` |
| TLS handshake failed | Wrong CA cert | Use ISRG Root X1 from `letsencrypt.org/certs/isrgrootx1.pem` |
| Not authorised (rc=5) | Wrong username or password | Username must be `device_id` UUID, password is the device token |
| No message received | Subscribed to wrong topic | Check `device_id` in topic matches the authenticated username |
| Message received once then stops | Clean session issue | Set `cleanSession=false` and re-subscribe on reconnect |

---

## 9. What MQTT does NOT carry

MQTT is used for **notifications only**. The following are never transmitted over MQTT:

- Money values that affect balances
- Offline transaction signing or verification
- Certificate issuance or validation
- Authentication tokens or private keys
- Any data that requires guaranteed delivery for financial integrity

All financial operations use HTTPS to `api.dompetgaruda.com`. If an MQTT message
is lost, the device can always poll the HTTPS API for its current balance and
certificate status. MQTT loss never causes money to be lost.