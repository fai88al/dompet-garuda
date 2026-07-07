#!/usr/bin/env bash
# Creates a new user and opens their ONLINE ledger account.
# Prerequisite: API server running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
# Replace ADMIN_API_TOKEN with the value from your .env file.

curl -s -X POST http://localhost:8080/admin/users \
  -H "Authorization: Bearer 1f970b85ec0c2ad03ff4cce906d90c3ec9ee28e58dc21868ee1658a26700c632" \
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
#   401 — wrong or missing Authorization header
#   409 — phone already registered
