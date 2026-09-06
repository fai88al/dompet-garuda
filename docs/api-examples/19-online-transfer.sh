#!/usr/bin/env bash
#
# Prerequisites:
#   - API running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#   - A registered device token (see 05-register-device.sh) with a funded sender
#     (see 03-topup.sh) and a receiver user id (see 01-create-user.sh)

BASE_URL="${BASE_URL:-http://localhost:8080}"
DEVICE_TOKEN="replace-with-sender-device-token"
RECEIVER_USER_ID="replace-with-receiver-user-id"
IDEMPOTENCY_KEY=$(uuidgen)

curl -s -X POST "${BASE_URL}/device/transfer" \
  -H "Authorization: Bearer ${DEVICE_TOKEN}" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"receiverUserId\": \"${RECEIVER_USER_ID}\", \"amount\": 50000}" | jq .

# Expected response (200 OK):
# {
#   "transactionId": 123,
#   "senderNewBalance": 450000
# }

# Replay with the SAME Idempotency-Key demonstrates idempotent behaviour — this
# returns the identical response above without posting a second time.
curl -s -X POST "${BASE_URL}/device/transfer" \
  -H "Authorization: Bearer ${DEVICE_TOKEN}" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"receiverUserId\": \"${RECEIVER_USER_ID}\", \"amount\": 50000}" | jq .

# Error cases:
#   401 - missing/invalid device Bearer token
#   400 - missing or non-UUID Idempotency-Key header
#   404 - receiverUserId does not exist
#   400 - receiverUserId equals the sender's own userId ("Cannot transfer to yourself")
#   400 - amount <= 0 or amount > transfer.online.max-amount-idr (10,000,000)
#   422 - sender's online balance is less than amount
