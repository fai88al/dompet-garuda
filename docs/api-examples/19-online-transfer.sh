#!/usr/bin/env bash
# FR18/FR19: online transfer to another user's balance.
#
# Prerequisites:
#   - API running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#   - A registered sender device id (see 02-register-device.sh) with a funded owner
#     (see 03-top-up.sh) and a registered receiver device id (see 02-register-device.sh)

BASE_URL="${BASE_URL:-http://localhost:8080}"
DEVICE_ID="A1B2C3D4E5F6"
RECEIVER_DEVICE_ID="C3D4E5F6A7B8"
IDEMPOTENCY_KEY=$(uuidgen)

curl -s -X POST "${BASE_URL}/device/transfer" \
  -H "Device-Id: ${DEVICE_ID}" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"receiverDeviceId\": \"${RECEIVER_DEVICE_ID}\", \"amount\": 5000}" | jq .

# Expected response (200 OK):
# {
#   "transactionId": 123,
#   "senderNewBalance": 450000
# }

# Replay with the SAME Idempotency-Key demonstrates idempotent behaviour — this
# returns the identical response above without posting a second time.
# curl -s -X POST "${BASE_URL}/device/transfer" \
#   -H "Device-Id: ${DEVICE_ID}" \
#   -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
#   -H "Content-Type: application/json" \
#   -d "{\"receiverDeviceId\": \"${RECEIVER_DEVICE_ID}\", \"amount\": 50000}" | jq .

# Error cases:
#   401 - missing Device-Id header, or device id not registered/not ACTIVE
#   400 - missing or non-UUID Idempotency-Key header
#   404 - receiverDeviceId does not exist
#   400 - receiverDeviceId's owner equals the sender's own userId ("Cannot transfer to yourself")
#   400 - amount <= 0 or amount > transfer.online.max-amount-idr (10,000,000)
#   422 - sender's online balance is less than amount
