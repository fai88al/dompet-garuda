#!/usr/bin/env bash
# FR5 — Upload offline transaction batch for settlement (POST /device/sync)
#
# Prerequisite: run 05-pouch-load.sh first so the device has an ACTIVE offline
# certificate. The certificateId is returned by that call.
#
# Variables to fill in:
#   DEVICE_TOKEN   — deviceToken returned by 02-register-device.sh
#   CERTIFICATE_ID — certificateId returned by 05-pouch-load.sh
#   RECEIVER_ID    — deviceId of the receiving device (from 02-register-device.sh)

DEVICE_TOKEN="b6046943e494e658d5ec81ecc8e5458a2895ad7962ae43cee5e561553fc7135a"
CERTIFICATE_ID="f47ac10b-58cc-4372-a567-0e02b2c3d479"
RECEIVER_ID="c3d4e5f6-a7b8-9012-cdef-123456789012"
BASE_URL="http://localhost:8080"

curl -s -X POST "$BASE_URL/device/sync" \
  -H "Authorization: Bearer $DEVICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"certificateId\": \"$CERTIFICATE_ID\",
    \"transactions\": [
      {
        \"offlineTxnId\":      \"$(uuidgen | tr '[:upper:]' '[:lower:]')\",
        \"receiverDeviceId\":  \"$RECEIVER_ID\",
        \"amount\":            50000,
        \"counter\":           1,
        \"deviceTimestamp\":   \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",
        \"senderSignature\":   \"base64-encoded-ed25519-sender-signature\",
        \"receiverSignature\": \"base64-encoded-ed25519-receiver-ack\"
      }
    ]
  }" | jq .

# Expected response (HTTP 202 Accepted):
# {
#   "batchId": "d4e5f6a7-b8c9-0123-defa-234567890123",
#   "status":  "PENDING"
# }
#
# The batch is stored in sync_inbox with status PENDING and remains PENDING
# until PR8 settlement is deployed. As of PR7, the worker polls sync_inbox
# every 5 s (SELECT … FOR UPDATE SKIP LOCKED), marks each row PROCESSING,
# logs the batch_id, then resets it to PENDING — no ledger writes yet.
#
# PR8 will replace the stub with: Ed25519 signature verify, ledger postings
# (OFFLINE_TRANSFER + POUCH_REFUND), and MQTT sync-result publish to
# wallet/{deviceId}/sync-result.
#
# Important behaviours:
#   * Late sync (uploaded after certificate expiry): accepted, stored with
#     synced_after_expiry = true. The worker decides settlement.
#   * Duplicate upload (same transactions uploaded twice): creates two rows
#     with different batch_ids. De-duplication is at the worker via
#     UNIQUE(sender_device_id, counter) in offline_transactions.
#
# Error cases:
#   malformed JSON body        → 400 Bad Request
#   missing certificateId      → 400 Bad Request
#   no/wrong device token      → 401 Unauthorized
