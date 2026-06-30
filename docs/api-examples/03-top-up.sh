#!/usr/bin/env bash
# FR2 — Top-up a user's ONLINE balance
# Prerequisite: run 01-create-user.sh first and substitute the returned userId below.

USER_ID="a1b2c3d4-0000-0000-0000-000000000001"   # replace with actual userId from 01-create-user.sh
ADMIN_TOKEN="dev-admin-token-here"                 # replace with ADMIN_API_TOKEN from .env
BASE_URL="http://localhost:8080"

curl -s -X POST "$BASE_URL/admin/users/$USER_ID/topup" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
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
