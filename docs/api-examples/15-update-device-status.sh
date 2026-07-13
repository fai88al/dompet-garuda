#!/usr/bin/env bash
# FR17 — Update device status (ACTIVE / SUSPENDED / LOCKED).
#
# Prerequisites:
#   - API running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#   - ADMIN_TOKEN from script 13-admin-login.sh
#   - DEVICE_ID from POST /admin/devices (script 02-register-device.sh) or GET /admin/devices
#
# Replace <ADMIN_TOKEN>, <DEVICE_ID>, and <STATUS> with real values.
# STATUS must be one of: ACTIVE, SUSPENDED, LOCKED

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="<ADMIN_TOKEN>"
DEVICE_ID="<DEVICE_ID>"
STATUS="SUSPENDED"

curl -s -X PATCH "${BASE_URL}/admin/devices/${DEVICE_ID}/status" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"status\": \"${STATUS}\"}" | jq .

# Expected response (200 OK):
# {
#   "deviceId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
#   "status": "SUSPENDED",
#   "updatedAt": "2026-07-13T10:05:00Z"
# }

# Invalid status (400):
# {"timestamp":...,"status":400,"error":"Bad Request","message":"Invalid status: BANNED. Must be one of: ACTIVE, SUSPENDED, LOCKED"}

# Device not found (404):
# {"timestamp":...,"status":404,"error":"Not Found","message":"Device not found: xxxxxxxx-..."}
