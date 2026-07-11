#!/usr/bin/env bash
# Lists all users with their derived online balances and device counts.
# Prerequisite: API server running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
# Replace ADMIN_API_TOKEN with the value from your .env file.

curl -s http://localhost:8080/admin/users \
  -H "Authorization: Bearer 1f970b85ec0c2ad03ff4cce906d90c3ec9ee28e58dc21868ee1658a26700c632" | jq .

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
#   401 — wrong or missing Authorization header
