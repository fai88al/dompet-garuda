#!/usr/bin/env bash
# Creates a new user and opens their ONLINE ledger account.
# Prerequisite: API server running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#               ADMIN_TOKEN — JWT obtained from 13-admin-login.sh (admin endpoints require
#               a Bearer JWT, not a static token — see CLAUDE.md §4/§12).

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="<ADMIN_TOKEN>"

curl -s -X POST "${BASE_URL}/admin/users" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "Muhammad Rizki",
    "phone": "+6282220463884"
  }' | jq .

# Expected response — HTTP 201 Created:
# {
#   "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
#   "fullName": "Budi Santoso",
#   "phone": "+62811000001",
#   "status": "ACTIVE",
#   "onlineAccountId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
#   "createdAt": "2026-06-23T07:00:00Z"
# }
#
# Error cases:
#   400 — missing/invalid field (e.g. phone format)
#   401 — missing/invalid/expired admin JWT
#   409 — phone already registered
