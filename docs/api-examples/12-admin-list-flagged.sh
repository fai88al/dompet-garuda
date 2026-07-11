#!/usr/bin/env bash
# Lists flagged transactions (anomalies from settlement/reconciliation).
# Default: unresolved only. Pass ?resolved=true to include resolved rows too.
# Prerequisite: API server running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
# Replace ADMIN_API_TOKEN with the value from your .env file.

# Unresolved flags only (default):
curl -s "http://localhost:8080/admin/flagged" \
  -H "Authorization: Bearer 1f970b85ec0c2ad03ff4cce906d90c3ec9ee28e58dc21868ee1658a26700c632" | jq .

# Include resolved flags too:
# curl -s "http://localhost:8080/admin/flagged?resolved=true" \
#   -H "Authorization: Bearer 1f970b85ec0c2ad03ff4cce906d90c3ec9ee28e58dc21868ee1658a26700c632" | jq .

# Expected response — HTTP 200 OK:
# [
#   {
#     "flagId": 1,
#     "reason": "RECON_MISMATCH",
#     "detail": "expected=200000 actual=100000",
#     "createdAt": "2026-06-23T09:00:00Z",
#     "offlineTxnId": null,
#     "batchId": null,
#     "certificateId": "c3d4e5f6-a7b8-9012-cdef-123456789012"
#   }
# ]
#
# reason is one of: OVER_LIMIT, BAD_SIGNATURE, COUNTER_REPLAY,
#   EXPIRED_CERT_LATE_SYNC, RECON_MISMATCH, MALFORMED
# offlineTxnId, batchId, certificateId are null when not applicable to the flag's reason.
# Empty array [] is returned when no flags match the filter.
#
# Error cases:
#   401 — wrong or missing Authorization header
