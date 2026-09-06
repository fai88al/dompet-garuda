#!/usr/bin/env bash
# Lists all devices with their active offline certificates (if any).
# Prerequisite: API server running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#               ADMIN_TOKEN — JWT obtained from 13-admin-login.sh.

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="<ADMIN_TOKEN>"

curl -s "${BASE_URL}/admin/devices" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq .

# Expected response — HTTP 200 OK:
# [
#   {
#     "deviceId": "B2C3D4E5F6A7",
#     "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
#     "userPhone": "+6282220463884",
#     "status": "ACTIVE",
#     "lastCounter": 12,
#     "registeredAt": "2026-06-23T07:05:00Z",
#     "activeCertificate": {
#       "certificateId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
#       "issuedAmount": 200000,
#       "expiresAt": "2026-06-24T07:05:00Z",
#       "status": "ACTIVE"
#     }
#   },
#   {
#     "deviceId": "D4E5F6A7B8C9",
#     "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
#     "userPhone": "+6282220463884",
#     "status": "ACTIVE",
#     "lastCounter": 0,
#     "registeredAt": "2026-06-25T08:00:00Z",
#     "activeCertificate": null
#   }
# ]
#
# activeCertificate is null when no ACTIVE certificate exists for the device.
# Empty array [] is returned when no devices exist.
#
# Error cases:
#   401 — missing/invalid/expired admin JWT
