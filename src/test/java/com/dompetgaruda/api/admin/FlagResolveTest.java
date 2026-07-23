package com.dompetgaruda.api.admin;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.admin.dto.FlagResolveResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FR16 — PATCH /admin/flagged/{flagId}/resolve.
 *
 * <p>Cases covered:
 * <ol>
 *   <li>Resolve an unresolved flag — returns 200 with resolved=true and a resolvedAt timestamp.</li>
 *   <li>Resolve the same flag again — returns 409 (already resolved).</li>
 *   <li>Resolve a non-existent flag — returns 404.</li>
 * </ol>
 */
class FlagResolveTest extends ApiIntegrationTestBase {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @Test
    void resolveFlag_happyPath_returns200WithResolvedTrue() {
        long flagId = insertFlag(false);

        ResponseEntity<FlagResolveResponse> resp = patch("/admin/flagged/" + flagId + "/resolve",
                FlagResolveResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().flagId()).isEqualTo(flagId);
        assertThat(resp.getBody().resolved()).isTrue();
        assertThat(resp.getBody().resolvedAt()).isNotNull();

        // Verify the DB row was actually updated
        Boolean dbResolved = jdbc.queryForObject(
                "SELECT resolved FROM flagged_transactions WHERE flag_id = ?",
                Boolean.class, flagId);
        assertThat(dbResolved).isTrue();
    }

    @Test
    void resolveFlag_alreadyResolved_returns409() {
        long flagId = insertFlag(true);

        ResponseEntity<String> resp = rest.exchange(
                "/admin/flagged/" + flagId + "/resolve",
                HttpMethod.PATCH,
                new HttpEntity<>(adminHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void resolveFlag_notFound_returns404() {
        ResponseEntity<String> resp = rest.exchange(
                "/admin/flagged/999999999/resolve",
                HttpMethod.PATCH,
                new HttpEntity<>(adminHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private long insertFlag(boolean resolved) {
        return jdbc.queryForObject(
                "INSERT INTO flagged_transactions (reason, detail, resolved) " +
                "VALUES ('MALFORMED', 'flag resolve test', ?) RETURNING flag_id",
                Long.class, resolved);
    }

    private <T> ResponseEntity<T> patch(String path, Class<T> responseType) {
        return rest.exchange(
                path,
                HttpMethod.PATCH,
                new HttpEntity<>(adminHeaders()),
                responseType);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(testAdminJwt());
        return h;
    }
}
