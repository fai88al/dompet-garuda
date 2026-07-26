package com.dompetgaruda.api.articles;

import com.dompetgaruda.api.articles.dto.ArticleResponse;
import com.dompetgaruda.api.articles.dto.CreateArticleRequest;
import com.dompetgaruda.api.articles.dto.UpdateArticleRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ArticleService {

    private static final Set<String> VALID_STATUSES = Set.of("DRAFT", "PUBLISHED");

    private final ArticleRepository articleRepository;
    private final SlugGenerator slugGenerator;

    public ArticleService(ArticleRepository articleRepository, SlugGenerator slugGenerator) {
        this.articleRepository = articleRepository;
        this.slugGenerator = slugGenerator;
    }

    @Transactional
    public ArticleResponse create(CreateArticleRequest req, UUID authorId) {
        Article article = new Article();
        article.setTitle(req.title());
        article.setSlug(slugGenerator.generate(req.title()));
        article.setContentHtml(req.contentHtml());
        article.setCoverImageUrl(req.coverImageUrl());
        article.setStatus("DRAFT");
        article.setAuthorId(authorId);
        articleRepository.save(article);
        return toResponse(article);
    }

    @Transactional(readOnly = true)
    public List<ArticleResponse> list(String status) {
        List<Article> articles = (status == null)
                ? articleRepository.findAllByOrderByCreatedAtDesc()
                : articleRepository.findAllByStatusOrderByCreatedAtDesc(validateStatus(status));
        return articles.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ArticleResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public ArticleResponse update(UUID id, UpdateArticleRequest req) {
        Article article = findOrThrow(id);
        article.setTitle(req.title());
        article.setContentHtml(req.contentHtml());
        article.setCoverImageUrl(req.coverImageUrl());
        articleRepository.save(article);
        return toResponse(article);
    }

    @Transactional
    public ArticleResponse publish(UUID id) {
        Article article = findOrThrow(id);
        if ("PUBLISHED".equals(article.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Article already published: " + id);
        }
        article.setStatus("PUBLISHED");
        article.setPublishedAt(OffsetDateTime.now());
        articleRepository.save(article);
        return toResponse(article);
    }

    @Transactional
    public ArticleResponse unpublish(UUID id) {
        Article article = findOrThrow(id);
        article.setStatus("DRAFT");
        article.setPublishedAt(null);
        articleRepository.save(article);
        return toResponse(article);
    }

    @Transactional
    public void delete(UUID id) {
        articleRepository.delete(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<ArticleResponse> listPublished() {
        return articleRepository.findAllByStatusOrderByPublishedAtDesc("PUBLISHED")
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ArticleResponse getPublishedBySlug(String slug) {
        Article article = articleRepository.findBySlugAndStatus(slug, "PUBLISHED")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found: " + slug));
        return toResponse(article);
    }

    private Article findOrThrow(UUID id) {
        return articleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found: " + id));
    }

    private String validateStatus(String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid status: " + status + ". Must be one of: DRAFT, PUBLISHED");
        }
        return status;
    }

    private ArticleResponse toResponse(Article a) {
        return new ArticleResponse(a.getId(), a.getTitle(), a.getSlug(), a.getContentHtml(),
                a.getCoverImageUrl(), a.getStatus(), a.getAuthorId(), a.getPublishedAt(),
                a.getCreatedAt(), a.getUpdatedAt());
    }
}
