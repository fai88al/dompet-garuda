package com.dompetgaruda.api.articles.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request body for creating a new article. New articles are always created with status DRAFT.")
public record CreateArticleRequest(
        @NotBlank
        @Schema(description = "Article title. Used to auto-generate the slug.", example = "How Offline Pouches Work")
        String title,

        @NotBlank
        @Schema(description = "Article body as HTML.", example = "<p>Article content goes here.</p>")
        String contentHtml,

        @Schema(description = "Optional cover image URL.", example = "https://cdn.example.com/covers/pouch.png")
        String coverImageUrl
) {}
