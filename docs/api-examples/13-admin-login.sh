#!/usr/bin/env bash
# FR15 — Admin login: exchange username + password for a signed JWT.
#
# Prerequisites:
#   - API running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#   - ADMIN_JWT_SECRET set in env
#
# Replace <EMAIL> and <PASSWORD> with the admin account credentials.
# Seeded accounts: rizki@dompetgaruda.com, faisal@dompetgaruda.com
# Temporary passwords are in the PR description for feat/admin-user-auth — rotate on first login.

BASE_URL="${BASE_URL:-http://localhost:8080}"

curl -s -X POST "${BASE_URL}/admin/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username": "rizki@dompetgaruda.com", "password": "<PASSWORD>"}' | jq .

# Expected response (200 OK):
# {
#   "token": "<JWT>",
#   "type": "Bearer",
#   "username": "rizki@dompetgaruda.com",
#   "role": "ADMIN"
# }
# Use the token as: Authorization: Bearer <JWT>

# Wrong password or unknown username (401 — same message to prevent enumeration):
# {
#   "message": "Invalid username or password"
# }

# After 5 consecutive failures from the same IP (429):
# {
#   "message": "Too many failed attempts. Try again in 5 minutes."
# }
