package com.dompetgaruda.api.articles.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "An article, as returned by admin and public article endpoints.")
public record ArticleResponse(
        @Schema(description = "Article UUID.") UUID id,
        @Schema(description = "Article title.") String title,
        @Schema(description = "URL-safe slug, unique, generated once at creation and never edited.") String slug,
        @Schema(description = "Article body as HTML.") String contentHtml,
        @Schema(description = "Optional cover image URL.") String coverImageUrl,
        @Schema(description = "DRAFT or PUBLISHED.", allowableValues = {"DRAFT", "PUBLISHED"}) String status,
        @Schema(description = "UUID of the admin_users row that authored this article.") UUID authorId,
        @Schema(description = "Timestamp the article was published; null while status is DRAFT.") OffsetDateTime publishedAt,
        @Schema(description = "Creation timestamp.") OffsetDateTime createdAt,
        @Schema(description = "Last update timestamp.") OffsetDateTime updatedAt
) {}
