#!/usr/bin/env bash
# FR3/FR13 — Load funds into the device's offline pouch
# Prerequisite: run 01-create-user.sh, 02-register-device.sh, and 03-top-up.sh first.
#   Use the deviceToken returned by 02-register-device.sh and ensure the user
#   has sufficient online balance from 03-top-up.sh.

DEVICE_TOKEN="device-bearer-token-here"   # replace with deviceToken from 02-register-device.sh
BASE_URL="http://localhost:8080"

curl -s -X POST "$BASE_URL/device/pouch/load" \
  -H "Authorization: Bearer $DEVICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 100000
  }' | jq .

# Expected response (HTTP 201):
# {
#   "certificateId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
#   "issuedAmount": 100000,
#   "expiresAt": "2026-07-07T14:30:00Z",
#   "serverSignature": "base64-encoded-ed25519-signature"
# }
#
# The serverSignature covers: "certificateId|deviceId|issuedAmount|expiresAtEpochSeconds"
# The device stores this certificate and presents it to prove server-authorised spend.
# Expires 24 hours after issuance.

# Error cases:
#   amount = 0 or negative                 → 400 Bad Request
#   amount > POUCH_MAX_AMOUNT_IDR          → 400 Bad Request
#   amount > online balance                → 422 Unprocessable Entity
#   active certificate already exists      → 409 Conflict (call pouch/refund first)
#   no/wrong device Bearer token           → 401 Unauthorized
