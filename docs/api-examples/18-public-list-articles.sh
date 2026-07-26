#!/usr/bin/env bash
# Public: list published articles, newest first. No authentication required.
#
# Prerequisites:
#   - API running (./mvnw spring-boot:run -Dspring-boot.run.profiles=api)

BASE_URL="${BASE_URL:-http://localhost:8080}"

curl -s -X GET "${BASE_URL}/public/articles" | jq .

# Expected response (200 OK):
# [
#   {
#     "id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
#     "title": "How Offline Pouches Work",
#     "slug": "how-offline-pouches-work",
#     "contentHtml": "<p>Article content goes here.</p>",
#     "coverImageUrl": "https://cdn.example.com/covers/pouch.png",
#     "status": "PUBLISHED",
#     "authorId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
#     "publishedAt": "2026-07-24T10:05:00Z",
#     "createdAt": "2026-07-24T10:00:00Z",
#     "updatedAt": "2026-07-24T10:05:00Z"
#   }
# ]
# DRAFT articles never appear in this list.
