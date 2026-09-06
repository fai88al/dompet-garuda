#!/usr/bin/env bash
# Gets a single user with their derived online balance and registered devices.
# Prerequisite: API server running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#               ADMIN_TOKEN — JWT obtained from 13-admin-login.sh.
# Replace USER_ID with a real value.

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="<ADMIN_TOKEN>"
USER_ID="a1b2c3d4-e5f6-7890-abcd-ef1234567890"

curl -s "${BASE_URL}/admin/users/${USER_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq .

# Expected response — HTTP 200 OK:
# {
#   "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
#   "fullName": "Muhammad Rizki",
#   "phone": "+6282220463884",
#   "status": "ACTIVE",
#   "onlineBalance": 150000,
#   "deviceCount": 1,
#   "createdAt": "2026-06-23T07:00:00Z",
#   "devices": [
#     {
#       "deviceId": "B2C3D4E5F6A7",
#       "status": "ACTIVE",
#       "registeredAt": "2026-06-23T07:05:00Z"
#     }
#   ]
# }
#
# Error cases:
#   401 — missing/invalid/expired admin JWT
#   404 — user not found
