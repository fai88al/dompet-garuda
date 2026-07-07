package com.dompetgaruda.api;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared base class for all api-profile integration tests.
 *
 * <p>Provides a single Testcontainers Postgres instance and a {@code @DynamicPropertySource}
 * that wires the datasource and the two properties required to construct {@link
 * com.dompetgaruda.api.wallet.PouchService} ({@code server.signing-key} and
 * {@code pouch.max-amount-idr}).
 *
 * <p>Subclasses must supply their own {@code @DynamicPropertySource} for
 * {@code admin.api-token} — different values across test classes prevent Spring
 * from reusing the same application context and mixing test data.
 *
 * <p>The {@code server.signing-key} default here is a valid but inert Ed25519 seed
 * (32 zero bytes). {@link com.dompetgaruda.api.wallet.PouchLoadTest} overrides it
 * with a real generated keypair so it can verify signatures.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
@Testcontainers
public abstract class ApiIntegrationTestBase {

    @Container
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("dompet")
                    .withUsername("dompet")
                    .withPassword("test");

    @DynamicPropertySource
    static void baseProps(DynamicPropertyRegistry registry) {
        registry.add("SPRING_DATASOURCE_URL",      postgres::getJdbcUrl);
        registry.add("SPRING_DATASOURCE_USERNAME",  postgres::getUsername);
        registry.add("SPRING_DATASOURCE_PASSWORD",  postgres::getPassword);
        // 32 zero-bytes — valid Ed25519 seed; lets PouchService start without a real key.
        registry.add("server.signing-key",          () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        // Production max per POUCH_MAX_AMOUNT_IDR.
        registry.add("pouch.max-amount-idr",        () -> 3_000_000L);
    }
}
