#!/usr/bin/env bash
# FR2 — Top-up a user's ONLINE balance
# Prerequisite: run 01-create-user.sh first and substitute the returned userId below.

USER_ID="4d5c4272-6a8a-4bab-b322-9a85d1e227e7"   # replace with actual userId from 01-create-user.sh
ADMIN_TOKEN="1f970b85ec0c2ad03ff4cce906d90c3ec9ee28e58dc21868ee1658a26700c632"                 # replace with ADMIN_API_TOKEN from .env
BASE_URL="http://localhost:8080"

curl -s -X POST "http://localhost:8080/admin/users/4d5c4272-6a8a-4bab-b322-9a85d1e227e7/topup" \
  -H "Authorization: Bearer 1f970b85ec0c2ad03ff4cce906d90c3ec9ee28e58dc21868ee1658a26700c632" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 50000,
    "reference": "manual-topup-001"
  }' | jq .

# Expected response (HTTP 201):
# {
#   "userId": "a1b2c3d4-0000-0000-0000-000000000001",
#   "onlineBalance": 50000,
#   "transactionId": 1,
#   "reference": "manual-topup-001"
# }

# onlineBalance is always derived from the ledger (SUM CREDIT − SUM DEBIT),
# never a cached field. Calling this endpoint twice with amount=50000 gives
# onlineBalance=100000 on the second response.

# Error cases:
#   amount = 0 or negative → 400 Bad Request
#   unknown userId         → 404 Not Found
#   no/wrong Bearer token  → 401 Unauthorized
