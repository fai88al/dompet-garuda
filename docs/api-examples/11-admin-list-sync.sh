#!/usr/bin/env bash
# Lists recent sync_inbox batches, newest first (default last 50, max 200 via ?limit=N).
# raw_payload is intentionally excluded — it may be large and contains device data.
# Prerequisite: API server running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#               ADMIN_TOKEN — JWT obtained from 13-admin-login.sh.

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="<ADMIN_TOKEN>"

# Last 50 batches (default):
curl -s "${BASE_URL}/admin/sync" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq .

# Custom limit (e.g. last 10):
# curl -s "${BASE_URL}/admin/sync?limit=10" \
#   -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq .

# Expected response — HTTP 200 OK:
# [
#   {
#     "batchId": "e5f6a7b8-c9d0-1234-ef01-345678901234",
#     "deviceId": "B2C3D4E5F6A7",
#     "status": "DONE",
#     "syncedAfterExpiry": false,
#     "receivedAt": "2026-06-23T08:00:00Z",
#     "processedAt": "2026-06-23T08:00:05Z",
#     "errorReason": null
#   }
# ]
#
# raw_payload is never returned — use the database directly if you need the full payload.
# Empty array [] is returned when no batches exist.
#
# Error cases:
#   401 — missing/invalid/expired admin JWT
