#!/usr/bin/env bash
# Publish a DRAFT article — sets status to PUBLISHED and published_at to now.
#
# Prerequisites:
#   - API running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
#   - ADMIN_TOKEN from script 13-admin-login.sh
#   - ARTICLE_ID from script 16-create-article.sh or GET /admin/articles
#
# Replace <ADMIN_TOKEN> and <ARTICLE_ID> with real values.

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="<ADMIN_TOKEN>"
ARTICLE_ID="<ARTICLE_ID>"

curl -s -X POST "${BASE_URL}/admin/articles/${ARTICLE_ID}/publish" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq .

# Expected response (200 OK):
# {
#   "id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
#   "status": "PUBLISHED",
#   "publishedAt": "2026-07-24T10:05:00Z",
#   ...
# }

# Already published (409):
# {"timestamp":...,"status":409,"error":"Conflict","message":"Article already published: xxxxxxxx-..."}

# Not found (404):
# {"timestamp":...,"status":404,"error":"Not Found","message":"Article not found: xxxxxxxx-..."}
