#!/usr/bin/env bash
# FR21 — POST /device/payment-request/{requestId}/pay
# Prerequisite: run 20-create-payment-request.sh first and capture its "requestId".
#               Payer needs a registered, ACTIVE device (05-register-device.sh) and
#               sufficient online balance (03-topup.sh).
# deviceId is a plain string (CLAUDE.md §1a), not a UUID.

BASE_URL="${BASE_URL:-http://localhost:8080}"
PAYER_DEVICE_ID="replace-with-payer-device-id"
REQUEST_ID="replace-with-requestId-from-20-create-payment-request.sh"
IDEMPOTENCY_KEY=$(uuidgen)

curl -s -X POST "${BASE_URL}/device/payment-request/${REQUEST_ID}/pay" \
  -H "Payer-Device-Id: ${PAYER_DEVICE_ID}" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" | jq .

# Expected response (200):
# {
#   "transactionId": 456,
#   "payerNewBalance": 375000
# }

# Replay with the SAME Idempotency-Key demonstrates idempotent behaviour — this
# returns the identical response above without posting a second time.
curl -s -X POST "${BASE_URL}/device/payment-request/${REQUEST_ID}/pay" \
  -H "Payer-Device-Id: ${PAYER_DEVICE_ID}" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" | jq .

# Error cases:
#   401 - missing Payer-Device-Id header, or device not registered/not ACTIVE
#   400 - missing or non-UUID Idempotency-Key header, or paying your own request
#   404 - requestId does not exist
#   409 - request already PAID or already EXPIRED
#   410 - request just expired at pay time (now marked EXPIRED)
#   422 - payer's online balance is less than the request amount
