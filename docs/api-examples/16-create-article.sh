#!/usr/bin/env bash
# Create an article (status=DRAFT). Slug is auto-generated from the title.
#
# Prerequisites:
#   - API running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#   - ADMIN_TOKEN from script 13-admin-login.sh (role ADMIN or WRITER)
#
# Replace <ADMIN_TOKEN> with a real value.

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="<ADMIN_TOKEN>"

curl -s -X POST "${BASE_URL}/admin/articles" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "How Offline Pouches Work",
    "contentHtml": "<p>Article content goes here.</p>",
    "coverImageUrl": "https://cdn.example.com/covers/pouch.png"
  }' | jq .

# Expected response (201 Created):
# {
#   "id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
#   "title": "How Offline Pouches Work",
#   "slug": "how-offline-pouches-work",
#   "contentHtml": "<p>Article content goes here.</p>",
#   "coverImageUrl": "https://cdn.example.com/covers/pouch.png",
#   "status": "DRAFT",
#   "authorId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
#   "publishedAt": null,
#   "createdAt": "2026-07-24T10:00:00Z",
#   "updatedAt": "2026-07-24T10:00:00Z"
# }

# Missing required field (400):
# {"timestamp":...,"status":400,"error":"Bad Request","message":"..."}

# Missing/invalid token (401):
# {"error":"Unauthorized"}

# Valid token but role is neither ADMIN nor WRITER (403):
# {"timestamp":...,"status":403,"error":"Forbidden","message":"Insufficient role"}
