#!/usr/bin/env bash
# FR15 — Admin login: exchange password for Bearer token.
#
# Prerequisites:
#   - API running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#   - ADMIN_API_TOKEN set in env (default in local .env)
#
# Replace <YOUR_ADMIN_PASSWORD> with the value of ADMIN_API_TOKEN.

BASE_URL="${BASE_URL:-http://localhost:8080}"

curl -s -X POST "${BASE_URL}/admin/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"password": "<YOUR_ADMIN_PASSWORD>"}' | jq .

# Expected response (200 OK):
# {
#   "token": "<ADMIN_API_TOKEN>",
#   "type": "Bearer"
# }

# Wrong password (401):
# {
#   "message": "Invalid password"
# }

# After 5 consecutive failures from the same IP (429):
# {
#   "message": "Too many failed attempts. Try again in 5 minutes."
# }
