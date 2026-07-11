#!/usr/bin/env bash
# Lists recent sync_inbox batches, newest first (default last 50, max 200 via ?limit=N).
# raw_payload is intentionally excluded — it may be large and contains device data.
# Prerequisite: API server running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
# Replace ADMIN_API_TOKEN with the value from your .env file.

# Last 50 batches (default):
curl -s "http://localhost:8080/admin/sync" \
  -H "Authorization: Bearer 1f970b85ec0c2ad03ff4cce906d90c3ec9ee28e58dc21868ee1658a26700c632" | jq .

# Custom limit (e.g. last 10):
# curl -s "http://localhost:8080/admin/sync?limit=10" \
#   -H "Authorization: Bearer 1f970b85ec0c2ad03ff4cce906d90c3ec9ee28e58dc21868ee1658a26700c632" | jq .

# Expected response — HTTP 200 OK:
# [
#   {
#     "batchId": "e5f6a7b8-c9d0-1234-ef01-345678901234",
#     "deviceId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
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
#   401 — wrong or missing Authorization header
