#!/usr/bin/env bash
# FR20 — POST /device/payment-request
# Prerequisite: a registered device token for the RECEIVER (see 05-register-device.sh).

BASE_URL="${BASE_URL:-http://localhost:8080}"
RECEIVER_DEVICE_TOKEN="replace-with-receiver-device-token"

curl -s -X POST "${BASE_URL}/device/payment-request" \
  -H "Authorization: Bearer ${RECEIVER_DEVICE_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"amount": 75000}' | jq .

# Expected response (201):
# {
#   "requestId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
#   "amount": 75000,
#   "nonce": "base64-random-string",
#   "expiresAt": "2026-08-17T10:15:00Z",
#   "qrPayload": "xxxxxxxx-.../receiverUserId/75000/base64-random-string"
# }
#
# Capture "requestId" from the response and use it in 21-pay-payment-request.sh.

# Error cases:
#   400 - amount <= 0
#   401 - missing/invalid device Bearer token
