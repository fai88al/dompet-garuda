package com.dompetgaruda.api.articles.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request body for updating an article's title, content, or cover image. The slug is not editable after creation.")
public record UpdateArticleRequest(
        @NotBlank
        @Schema(description = "Article title.", example = "How Offline Pouches Work")
        String title,

        @NotBlank
        @Schema(description = "Article body as HTML.", example = "<p>Updated content.</p>")
        String contentHtml,

        @Schema(description = "Optional cover image URL.", example = "https://cdn.example.com/covers/pouch.png")
        String coverImageUrl
) {}
