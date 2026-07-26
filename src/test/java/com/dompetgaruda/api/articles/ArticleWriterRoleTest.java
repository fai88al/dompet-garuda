package com.dompetgaruda.api.articles;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.articles.dto.ArticleResponse;
import com.dompetgaruda.api.articles.dto.CreateArticleRequest;
import com.dompetgaruda.api.articles.dto.UpdateArticleRequest;
import com.dompetgaruda.api.auth.LoginAttemptTracker;
import com.dompetgaruda.api.auth.dto.LoginRequest;
import com.dompetgaruda.api.auth.dto.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the WRITER role (introduced for the articles feature) works end to end via the
 * real login endpoint. Uses a disposable test-fixture WRITER account rather than the
 * migration-seeded rizkiwriter@dompetgaruda.com account, because that account's temporary
 * password is documented only in the PR description and must never be committed (CLAUDE.md §7.9).
 */
class ArticleWriterRoleTest extends ApiIntegrationTestBase {

    private static final String TEST_WRITER_USERNAME = "test-writer-login@dompetgaruda.com";
    private static final String TEST_WRITER_PASSWORD = "Test$WriterPass1";
    private static final String TEST_WRITER_PASSWORD_HASH =
            new BCryptPasswordEncoder(10).encode(TEST_WRITER_PASSWORD);

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoginAttemptTracker attemptTracker;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM articles WHERE author_id IN (SELECT id FROM admin_users WHERE username = ?)", TEST_WRITER_USERNAME);
        jdbc.update("DELETE FROM admin_users WHERE username = ?", TEST_WRITER_USERNAME);
        jdbc.update(
                "INSERT INTO admin_users (id, username, password_hash, role) " +
                "VALUES (gen_random_uuid(), ?, ?, 'WRITER')",
                TEST_WRITER_USERNAME, TEST_WRITER_PASSWORD_HASH);
        attemptTracker.clearAll();
    }

    @Test
    void writerAccount_canLoginCreateEditAndPublishArticle() {
        String token = login();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        ResponseEntity<ArticleResponse> createResp = rest.exchange(
                "/admin/articles", HttpMethod.POST,
                new HttpEntity<>(new CreateArticleRequest("Writer Article", "<p>draft</p>", null), headers),
                ArticleResponse.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ArticleResponse created = createResp.getBody();
        assertThat(created.status()).isEqualTo("DRAFT");

        ResponseEntity<ArticleResponse> updateResp = rest.exchange(
                "/admin/articles/" + created.id(), HttpMethod.PATCH,
                new HttpEntity<>(new UpdateArticleRequest("Writer Article Edited", "<p>edited</p>", null), headers),
                ArticleResponse.class);
        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResp.getBody().title()).isEqualTo("Writer Article Edited");

        ResponseEntity<ArticleResponse> publishResp = rest.exchange(
                "/admin/articles/" + created.id() + "/publish", HttpMethod.POST,
                new HttpEntity<>(headers), ArticleResponse.class);
        assertThat(publishResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publishResp.getBody().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void migrationSeededWriterAccount_hasWriterRoleAndBcryptHash() {
        String role = jdbc.queryForObject(
                "SELECT role FROM admin_users WHERE username = 'rizkiwriter@dompetgaruda.com'", String.class);
        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM admin_users WHERE username = 'rizkiwriter@dompetgaruda.com'", String.class);

        assertThat(role).isEqualTo("WRITER");
        assertThat(hash).startsWith("$2a$10$");
    }

    private String login() {
        ResponseEntity<LoginResponse> resp = rest.postForEntity(
                "/admin/auth/login",
                new HttpEntity<>(new LoginRequest(TEST_WRITER_USERNAME, TEST_WRITER_PASSWORD), jsonHeaders()),
                LoginResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().role()).isEqualTo("WRITER");
        return resp.getBody().token();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
