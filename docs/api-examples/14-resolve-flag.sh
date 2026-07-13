#!/usr/bin/env bash
# FR16 — Resolve a flagged transaction.
#
# Prerequisites:
#   - API running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#   - ADMIN_TOKEN from script 13-admin-login.sh
#   - FLAG_ID from GET /admin/flagged (script 12-admin-list-flagged.sh)
#
# Replace <ADMIN_TOKEN> with the Bearer token and <FLAG_ID> with the flag's primary key.

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="<ADMIN_TOKEN>"
FLAG_ID="<FLAG_ID>"

curl -s -X PATCH "${BASE_URL}/admin/flagged/${FLAG_ID}/resolve" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" | jq .

# Expected response (200 OK):
# {
#   "flagId": 1,
#   "resolved": true,
#   "resolvedAt": "2026-07-13T10:00:00Z"
# }

# Flag not found (404):
# {"timestamp":...,"status":404,"error":"Not Found","message":"Flag not found: 999"}

# Already resolved (409):
# {"timestamp":...,"status":409,"error":"Conflict","message":"Flag already resolved: 1"}
