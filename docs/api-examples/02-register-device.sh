#!/usr/bin/env bash
# Registers an ESP32 device against an existing user.
# The device's Ed25519 public key comes from the firmware at first setup.
# The returned deviceToken is shown ONCE — provision it onto the device immediately.
# It also doubles as the device's MQTT password (username=deviceId) — FR25 provisions this
# automatically as a mandatory part of registration; a provisioning failure rolls the whole
# registration back and returns 503 instead of 201 (see docs/MQTT_CONTRACT.md).
#
# Prerequisite: run 01-create-user.sh first and copy the returned userId below.
#               ADMIN_TOKEN — JWT obtained from 13-admin-login.sh.

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="<ADMIN_TOKEN>"

curl -s -X POST "${BASE_URL}/admin/devices" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "4d5c4272-6a8a-4bab-b322-9a85d1e227e7",
    "deviceId": "A1B2C3D4E5F6",
    "publicKey": "MCowBQYDK2VwAyEA47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=",
    "label": "Device 1"
  }' | jq .

# deviceId is sourced from the hardware/firmware team's own identifier scheme (e.g.
# MAC-derived), NOT a UUID (CLAUDE.md §1a). It must not contain '/' or '|' — both have
# structural meaning elsewhere (MQTT topic levels, offline signature message delimiter).

# Expected response — HTTP 201 Created:
# {
#   "deviceId": "A1B2C3D4E5F6",
#   "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
#   "deviceLabel": "Device 1",
#   "pouchAccountId": "d4e5f6a7-b8c9-0123-defa-234567890123",
#   "registeredAt": "2026-06-23T07:01:00Z",
#   "deviceToken": "a3f8e2b1c94d6e7f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3"
# }
#
# Error cases:
#   400 — missing/invalid field, deviceId contains '/' or '|' (FR27), or publicKey is not
#         valid Base64 / does not decode to a well-formed X.509 Ed25519 public key
#   401 — missing/invalid/expired admin JWT
#   404 — userId not found
#   409 — publicKey or deviceId already registered to another device
#   422 — user already has 3 devices (maximum)
#   503 — MQTT credential provisioning failed (FR25); nothing was persisted, safe to retry
