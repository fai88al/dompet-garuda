package com.dompetgaruda.api.articles;

import com.dompetgaruda.api.articles.dto.ArticleResponse;
import com.dompetgaruda.api.articles.dto.CreateArticleRequest;
import com.dompetgaruda.api.articles.dto.UpdateArticleRequest;
import com.dompetgaruda.api.auth.RoleGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/admin/articles")
@Profile("api")
@Tag(name = "Articles", description = "Admin/writer endpoints for managing articles. Require a Bearer JWT with role ADMIN or WRITER.")
public class ArticleController {

    private static final Set<String> WRITER_ROLES = Set.of("ADMIN", "WRITER");

    private final ArticleService articleService;

    public ArticleController(ArticleService articleService) {
        this.articleService = articleService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new article.", description = "New articles are always created with status DRAFT. The slug is auto-generated from the title.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Article created."),
        @ApiResponse(responseCode = "400", description = "Validation failure — missing required field."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @ApiResponse(responseCode = "403", description = "Valid token but role is neither ADMIN nor WRITER.")
    })
    public ArticleResponse create(@Valid @RequestBody CreateArticleRequest request) {
        RoleGuard.requireAnyRole(WRITER_ROLES);
        return articleService.create(request, RoleGuard.currentUserId());
    }

    @GetMapping
    @Operation(summary = "List all articles, newest first.", description = "Returns articles of any status. Optional ?status=DRAFT|PUBLISHED filter.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Article list (empty array if none)."),
        @ApiResponse(responseCode = "400", description = "Invalid status filter value."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @ApiResponse(responseCode = "403", description = "Valid token but role is neither ADMIN nor WRITER.")
    })
    public List<ArticleResponse> list(
            @Parameter(description = "Optional status filter: DRAFT or PUBLISHED.")
            @RequestParam(required = false) String status) {
        RoleGuard.requireAnyRole(WRITER_ROLES);
        return articleService.list(status);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one article by id, for editing or preview.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Article found."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @ApiResponse(responseCode = "403", description = "Valid token but role is neither ADMIN nor WRITER."),
        @ApiResponse(responseCode = "404", description = "Article not found.")
    })
    public ArticleResponse get(@Parameter(description = "Article UUID.") @PathVariable UUID id) {
        RoleGuard.requireAnyRole(WRITER_ROLES);
        return articleService.get(id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update an article's title, content, or cover image.", description = "The slug is not editable after creation.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Article updated."),
        @ApiResponse(responseCode = "400", description = "Validation failure — missing required field."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @ApiResponse(responseCode = "403", description = "Valid token but role is neither ADMIN nor WRITER."),
        @ApiResponse(responseCode = "404", description = "Article not found.")
    })
    public ArticleResponse update(
            @Parameter(description = "Article UUID.") @PathVariable UUID id,
            @Valid @RequestBody UpdateArticleRequest request) {
        RoleGuard.requireAnyRole(WRITER_ROLES);
        return articleService.update(id, request);
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "Publish a DRAFT article.", description = "Sets status to PUBLISHED and published_at to now.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Article published."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @ApiResponse(responseCode = "403", description = "Valid token but role is neither ADMIN nor WRITER."),
        @ApiResponse(responseCode = "404", description = "Article not found."),
        @ApiResponse(responseCode = "409", description = "Article is already published.")
    })
    public ArticleResponse publish(@Parameter(description = "Article UUID.") @PathVariable UUID id) {
        RoleGuard.requireAnyRole(WRITER_ROLES);
        return articleService.publish(id);
    }

    @PostMapping("/{id}/unpublish")
    @Operation(summary = "Unpublish a PUBLISHED article.", description = "Sets status back to DRAFT and clears published_at.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Article unpublished."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @ApiResponse(responseCode = "403", description = "Valid token but role is neither ADMIN nor WRITER."),
        @ApiResponse(responseCode = "404", description = "Article not found.")
    })
    public ArticleResponse unpublish(@Parameter(description = "Article UUID.") @PathVariable UUID id) {
        RoleGuard.requireAnyRole(WRITER_ROLES);
        return articleService.unpublish(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Hard-delete an article.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Article deleted."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @ApiResponse(responseCode = "403", description = "Valid token but role is neither ADMIN nor WRITER."),
        @ApiResponse(responseCode = "404", description = "Article not found.")
    })
    public void delete(@Parameter(description = "Article UUID.") @PathVariable UUID id) {
        RoleGuard.requireAnyRole(WRITER_ROLES);
        articleService.delete(id);
    }
}
