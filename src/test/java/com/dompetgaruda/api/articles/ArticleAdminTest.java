package com.dompetgaruda.api.articles;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.articles.dto.ArticleResponse;
import com.dompetgaruda.api.articles.dto.CreateArticleRequest;
import com.dompetgaruda.api.articles.dto.UpdateArticleRequest;
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

class ArticleAdminTest extends ApiIntegrationTestBase {

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
    void create_generatesSlugFromTitle() {
        ArticleResponse article = create("Hello World! This Is A Test");

        assertThat(article.slug()).isEqualTo("hello-world-this-is-a-test");
        assertThat(article.status()).isEqualTo("DRAFT");
        assertThat(article.publishedAt()).isNull();
    }

    @Test
    void create_duplicateTitle_getsSuffixedSlug() {
        ArticleResponse first = create("Duplicate Title");
        ArticleResponse second = create("Duplicate Title");
        ArticleResponse third = create("Duplicate Title");

        assertThat(first.slug()).isEqualTo("duplicate-title");
        assertThat(second.slug()).isEqualTo("duplicate-title-2");
        assertThat(third.slug()).isEqualTo("duplicate-title-3");
    }

    @Test
    void adminArticleEndpoints_noToken_return401() {
        UUID randomId = UUID.randomUUID();

        assertThat(postRaw("/admin/articles", new CreateArticleRequest("T", "<p>x</p>", null)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getRaw("/admin/articles").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getRaw("/admin/articles/" + randomId).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/admin/articles/" + randomId, HttpMethod.PATCH,
                new HttpEntity<>(new UpdateArticleRequest("T", "<p>x</p>", null), jsonHeaders()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postRaw("/admin/articles/" + randomId + "/publish", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postRaw("/admin/articles/" + randomId + "/unpublish", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/admin/articles/" + randomId, HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders()), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void get_returnsCreatedArticle() {
        ArticleResponse created = create("Gettable Article");
        ResponseEntity<ArticleResponse> resp = rest.exchange(
                "/admin/articles/" + created.id(), HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), ArticleResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().id()).isEqualTo(created.id());
    }

    @Test
    void list_filtersByStatus() {
        ArticleResponse draft = create("Draft Only Article " + UUID.randomUUID());
        ArticleResponse toPublish = create("To Publish Article " + UUID.randomUUID());
        publish(toPublish.id());

        List<ArticleResponse> drafts = list("DRAFT");
        List<ArticleResponse> published = list("PUBLISHED");

        assertThat(drafts).extracting(ArticleResponse::id).contains(draft.id());
        assertThat(drafts).extracting(ArticleResponse::id).doesNotContain(toPublish.id());
        assertThat(published).extracting(ArticleResponse::id).contains(toPublish.id());
    }

    @Test
    void update_changesFields() {
        ArticleResponse created = create("Original Title");
        ResponseEntity<ArticleResponse> resp = rest.exchange(
                "/admin/articles/" + created.id(), HttpMethod.PATCH,
                new HttpEntity<>(new UpdateArticleRequest("Updated Title", "<p>updated</p>", "https://x/img.png"), adminHeaders()),
                ArticleResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().title()).isEqualTo("Updated Title");
        assertThat(resp.getBody().contentHtml()).isEqualTo("<p>updated</p>");
        assertThat(resp.getBody().coverImageUrl()).isEqualTo("https://x/img.png");
        assertThat(resp.getBody().slug()).isEqualTo(created.slug());
    }

    @Test
    void publish_setsPublishedAt_unpublish_clearsIt() {
        ArticleResponse created = create("Publish Cycle Article");

        ArticleResponse published = publish(created.id());
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.publishedAt()).isNotNull();

        ArticleResponse unpublished = unpublish(created.id());
        assertThat(unpublished.status()).isEqualTo("DRAFT");
        assertThat(unpublished.publishedAt()).isNull();
    }

    @Test
    void publish_alreadyPublished_returns409() {
        ArticleResponse created = create("Double Publish Article");
        publish(created.id());

        ResponseEntity<String> resp = rest.exchange(
                "/admin/articles/" + created.id() + "/publish", HttpMethod.POST,
                new HttpEntity<>(adminHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void delete_removesArticle() {
        ArticleResponse created = create("Deletable Article");

        ResponseEntity<Void> deleteResp = rest.exchange(
                "/admin/articles/" + created.id(), HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders()), Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> getResp = rest.exchange(
                "/admin/articles/" + created.id(), HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), String.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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

    private ArticleResponse publish(UUID id) {
        ResponseEntity<ArticleResponse> resp = rest.exchange(
                "/admin/articles/" + id + "/publish", HttpMethod.POST,
                new HttpEntity<>(adminHeaders()), ArticleResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private ArticleResponse unpublish(UUID id) {
        ResponseEntity<ArticleResponse> resp = rest.exchange(
                "/admin/articles/" + id + "/unpublish", HttpMethod.POST,
                new HttpEntity<>(adminHeaders()), ArticleResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private List<ArticleResponse> list(String status) {
        String path = status == null ? "/admin/articles" : "/admin/articles?status=" + status;
        ResponseEntity<List<ArticleResponse>> resp = rest.exchange(
                path, HttpMethod.GET, new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<ArticleResponse>>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private ResponseEntity<String> postRaw(String path, Object body) {
        return rest.postForEntity(path, new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    private ResponseEntity<String> getRaw(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(testAdminJwt());
        return h;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
