package com.dompetgaruda.api.articles;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArticleRepository extends JpaRepository<Article, UUID> {
    boolean existsBySlug(String slug);
    List<Article> findAllByOrderByCreatedAtDesc();
    List<Article> findAllByStatusOrderByCreatedAtDesc(String status);
    List<Article> findAllByStatusOrderByPublishedAtDesc(String status);
    Optional<Article> findBySlugAndStatus(String slug, String status);
}
