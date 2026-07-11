#!/usr/bin/env bash
# Gets a single user with their derived online balance and registered devices.
# Prerequisite: API server running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
# Replace ADMIN_API_TOKEN and USER_ID with real values.

USER_ID="a1b2c3d4-e5f6-7890-abcd-ef1234567890"

curl -s "http://localhost:8080/admin/users/${USER_ID}" \
  -H "Authorization: Bearer 1f970b85ec0c2ad03ff4cce906d90c3ec9ee28e58dc21868ee1658a26700c632" | jq .

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
#       "deviceId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
#       "status": "ACTIVE",
#       "registeredAt": "2026-06-23T07:05:00Z"
#     }
#   ]
# }
#
# Error cases:
#   401 — wrong or missing Authorization header
#   404 — user not found
