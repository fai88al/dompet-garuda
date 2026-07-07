#!/usr/bin/env bash
# FR14 — Balance enquiry (GET /device/balance)
#
# Prerequisites:
#   - API server running on localhost:8080 (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#   - A device registered and topped up (see 01-create-user.sh, 02-register-device.sh, 03-top-up.sh)
#   - DEVICE_TOKEN set to the token returned by 02-register-device.sh

DEVICE_TOKEN="b6046943e494e658d5ec81ecc8e5458a2895ad7962ae43cee5e561553fc7135a"

curl -s -X GET http://localhost:8080/device/balance \
  -H "Authorization: Bearer b6046943e494e658d5ec81ecc8e5458a2895ad7962ae43cee5e561553fc7135a" | jq .

# Expected response (200 OK):
# {
#   "onlineBalance": 150000,
#   "pouchCommitted": 0
# }
#
# onlineBalance  — SUM(CREDIT) − SUM(DEBIT) over the user's ONLINE ledger account.
#                  This is the authoritative server-side figure.
# pouchCommitted — issued_amount on the device's ACTIVE offline certificate (0 if none).
#                  The server does not see offline spends until the device syncs.
#
# Error responses:
#   401 { "status": 401, "error": "Unauthorized" }  — missing or invalid device token
