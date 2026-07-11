#!/usr/bin/env bash
# Lists all devices with their active offline certificates (if any).
# Prerequisite: API server running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
# Replace ADMIN_API_TOKEN with the value from your .env file.

curl -s http://localhost:8080/admin/devices \
  -H "Authorization: Bearer 1f970b85ec0c2ad03ff4cce906d90c3ec9ee28e58dc21868ee1658a26700c632" | jq .

# Expected response — HTTP 200 OK:
# [
#   {
#     "deviceId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
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
#     "deviceId": "d4e5f6a7-b8c9-0123-def0-234567890123",
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
#   401 — wrong or missing Authorization header
