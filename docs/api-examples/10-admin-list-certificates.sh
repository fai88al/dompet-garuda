#!/usr/bin/env bash
# Lists offline certificates ordered by issued_at DESC.
# Optional ?status= filter: ACTIVE | SETTLED | EXPIRED | REVOKED
# Prerequisite: API server running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
# Replace ADMIN_API_TOKEN with the value from your .env file.

# All certificates:
curl -s "http://localhost:8080/admin/certificates" \
  -H "Authorization: Bearer 1f970b85ec0c2ad03ff4cce906d90c3ec9ee28e58dc21868ee1658a26700c632" | jq .

# Filter by status (e.g. ACTIVE only):
# curl -s "http://localhost:8080/admin/certificates?status=ACTIVE" \
#   -H "Authorization: Bearer 1f970b85ec0c2ad03ff4cce906d90c3ec9ee28e58dc21868ee1658a26700c632" | jq .

# Expected response — HTTP 200 OK:
# [
#   {
#     "certificateId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
#     "deviceId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
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
#   401 — wrong or missing Authorization header
