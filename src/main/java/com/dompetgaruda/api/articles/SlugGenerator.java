package com.dompetgaruda.api.articles;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class SlugGenerator {

    private final ArticleRepository articleRepository;

    public SlugGenerator(ArticleRepository articleRepository) {
        this.articleRepository = articleRepository;
    }

    public String generate(String title) {
        String base = slugify(title);
        String candidate = base;
        int suffix = 2;
        while (articleRepository.existsBySlug(candidate)) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    static String slugify(String title) {
        String lower = title.toLowerCase(Locale.ROOT).trim();
        String hyphenated = lower.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return hyphenated.isEmpty() ? "article" : hyphenated;
    }
}
