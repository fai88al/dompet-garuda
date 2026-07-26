package com.dompetgaruda.api.articles;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.articles.dto.ArticleResponse;
import com.dompetgaruda.api.articles.dto.CreateArticleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PublicArticleTest extends ApiIntegrationTestBase {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedTestAdminAuthor() {
        jdbc.update("""
                INSERT INTO admin_users (id, username, password_hash, role)
                VALUES (?, 'test-admin@dompetgaruda.com',
                        '$2a$10$JKa/3KW7wsfjIrLkTmfiBeNvdJUJO25MEVhMnKOi.Zg/1DnN6Y3be', 'ADMIN')
                ON CONFLICT (id) DO NOTHING
                """, TEST_ADMIN_ID);
    }

    @Test
    void listPublished_neverIncludesDraftArticles() {
        String uniqueMarker = UUID.randomUUID().toString();
        ArticleResponse draft = create("Draft Marker " + uniqueMarker);
        ArticleResponse published = create("Published Marker " + uniqueMarker);
        publish(published.id());

        List<ArticleResponse> publishedList = listPublic();

        assertThat(publishedList).extracting(ArticleResponse::id).contains(published.id());
        assertThat(publishedList).extracting(ArticleResponse::id).doesNotContain(draft.id());
        assertThat(publishedList).extracting(ArticleResponse::status).containsOnly("PUBLISHED");
    }

    @Test
    void getBySlug_draftArticle_returns404() {
        ArticleResponse draft = create("Draft Slug Article " + UUID.randomUUID());

        ResponseEntity<String> resp = rest.getForEntity("/public/articles/" + draft.slug(), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getBySlug_unknownSlug_returns404() {
        ResponseEntity<String> resp = rest.getForEntity("/public/articles/does-not-exist-" + UUID.randomUUID(), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getBySlug_publishedArticle_returns200() {
        ArticleResponse published = create("Published Slug Article " + UUID.randomUUID());
        publish(published.id());

        ResponseEntity<ArticleResponse> resp = rest.getForEntity(
                "/public/articles/" + published.slug(), ArticleResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().slug()).isEqualTo(published.slug());
        assertThat(resp.getBody().status()).isEqualTo("PUBLISHED");
    }

    // --- helpers ---

    private ArticleResponse create(String title) {
        ResponseEntity<ArticleResponse> resp = rest.exchange(
                "/admin/articles", HttpMethod.POST,
                new HttpEntity<>(new CreateArticleRequest(title, "<p>body</p>", null), adminHeaders()),
                ArticleResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private void publish(UUID id) {
        ResponseEntity<ArticleResponse> resp = rest.exchange(
                "/admin/articles/" + id + "/publish", HttpMethod.POST,
                new HttpEntity<>(adminHeaders()), ArticleResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private List<ArticleResponse> listPublic() {
        ResponseEntity<List<ArticleResponse>> resp = rest.exchange(
                "/public/articles", HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<List<ArticleResponse>>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(testAdminJwt());
        return h;
    }
}
