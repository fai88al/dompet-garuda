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
# The batch is stored in sync_inbox with status PENDING. The worker polls
# sync_inbox every 5 s (SELECT … FOR UPDATE SKIP LOCKED), validates each
# transaction's Ed25519 signatures, posts OFFLINE_TRANSFER ledger entries
# (one per valid transaction), issues a POUCH_REFUND for any unspent pouch
# balance, and marks the batch DONE. Rejected transactions are flagged in
# flagged_transactions with a reason code (COUNTER_REPLAY, BAD_SIGNATURE,
# OVER_LIMIT, MALFORMED) and never posted to the ledger.
#
# After the DB commit the worker publishes a sync-result notification to
# MQTT topic  wallet/{deviceId}/sync-result  (QoS 1, TLS port 8883):
#   { "batchId": "<batchId>", "status": "SETTLED" }   on success
#   { "batchId": "<batchId>", "status": "FAILED",
#     "detail": "<reason>" }                          on failure
# The device subscribes to this topic to learn the outcome without polling.
# MQTT carries no financial authority — it is a hint only (§7.8).
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
