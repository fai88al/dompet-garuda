package com.dompetgaruda.api.articles;

import com.dompetgaruda.api.articles.dto.ArticleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/public/articles")
@Profile("api")
@Tag(name = "Public", description = "Public read-only article endpoints. No authentication required.")
public class PublicArticleController {

    private final ArticleService articleService;

    public PublicArticleController(ArticleService articleService) {
        this.articleService = articleService;
    }

    @GetMapping
    @Operation(summary = "List published articles, newest first.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Published article list (empty array if none).")
    })
    public List<ArticleResponse> listPublished() {
        return articleService.listPublished();
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get one published article by slug.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Published article found."),
        @ApiResponse(responseCode = "404", description = "Article not found or not published.")
    })
    public ArticleResponse getBySlug(@Parameter(description = "Article slug.") @PathVariable String slug) {
        return articleService.getPublishedBySlug(slug);
    }
}
