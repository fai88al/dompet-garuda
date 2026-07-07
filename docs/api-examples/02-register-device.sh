#!/usr/bin/env bash
# Registers an ESP32 device against an existing user.
# The device's Ed25519 public key comes from the firmware at first setup.
# The returned deviceToken is shown ONCE — provision it onto the device immediately.
#
# Prerequisite: run 01-create-user.sh first and copy the returned userId below.

curl -s -X POST http://localhost:8080/admin/devices \
  -H "Authorization: Bearer 1f970b85ec0c2ad03ff4cce906d90c3ec9ee28e58dc21868ee1658a26700c632" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "4d5c4272-6a8a-4bab-b322-9a85d1e227e7",
    "publicKey": "MCowBQYDK2VwAyEA47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=",
    "label": "Device 1"
  }' | jq .

# Expected response — HTTP 201 Created:
# {
#   "deviceId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
#   "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
#   "deviceLabel": "Device 1",
#   "pouchAccountId": "d4e5f6a7-b8c9-0123-defa-234567890123",
#   "registeredAt": "2026-06-23T07:01:00Z",
#   "deviceToken": "a3f8e2b1c94d6e7f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3"
# }
#
# Error cases:
#   400 — missing/invalid field
#   401 — wrong or missing Authorization header
#   404 — userId not found
#   409 — publicKey already registered to another device
#   422 — user already has 3 devices (maximum)
