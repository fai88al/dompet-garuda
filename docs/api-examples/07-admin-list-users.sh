#!/usr/bin/env bash
# Lists all users with their derived online balances and device counts.
# Prerequisite: API server running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#               ADMIN_TOKEN — JWT obtained from 13-admin-login.sh.

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="<ADMIN_TOKEN>"

curl -s "${BASE_URL}/admin/users" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq .

# Expected response — HTTP 200 OK:
# [
#   {
#     "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
#     "fullName": "Muhammad Rizki",
#     "phone": "+6282220463884",
#     "status": "ACTIVE",
#     "onlineBalance": 150000,
#     "deviceCount": 2,
#     "createdAt": "2026-06-23T07:00:00Z"
#   }
# ]
#
# onlineBalance is derived from ledger entries (SUM CREDIT − SUM DEBIT). Never a stored column.
# Empty array [] is returned when no users exist.
#
# Error cases:
#   401 — missing/invalid/expired admin JWT
