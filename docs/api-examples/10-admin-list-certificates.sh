#!/usr/bin/env bash
# Lists offline certificates ordered by issued_at DESC.
# Optional ?status= filter: ACTIVE | SETTLED | EXPIRED | REVOKED
# Prerequisite: API server running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#               ADMIN_TOKEN — JWT obtained from 13-admin-login.sh.

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="<ADMIN_TOKEN>"

# All certificates:
curl -s "${BASE_URL}/admin/certificates" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq .

# Filter by status (e.g. ACTIVE only):
# curl -s "${BASE_URL}/admin/certificates?status=ACTIVE" \
#   -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq .

# Expected response — HTTP 200 OK:
# [
#   {
#     "certificateId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
#     "deviceId": "B2C3D4E5F6A7",
#     "userPhone": "+6282220463884",
#     "issuedAmount": 200000,
#     "status": "ACTIVE",
#     "issuedAt": "2026-06-23T07:05:00Z",
#     "expiresAt": "2026-06-24T07:05:00Z",
#     "settledAt": null
#   }
# ]
#
# settledAt is null for non-SETTLED certificates.
# Empty array [] is returned when no certificates match the filter.
#
# Error cases:
#   401 — missing/invalid/expired admin JWT
