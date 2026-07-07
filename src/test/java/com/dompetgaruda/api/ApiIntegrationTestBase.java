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
 * <p>{@link #SIGNING_KEY_SEED} is the Ed25519 seed this base class configures for
 * {@code server.signing-key}. {@link com.dompetgaruda.api.wallet.PouchLoadTest} derives
 * its verification keypair from this constant so that the public key it uses to verify
 * signatures matches exactly the private key PouchService uses to produce them.
 * No subclass should override {@code server.signing-key}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
@Testcontainers
public abstract class ApiIntegrationTestBase {

    /**
     * Base64-encoded 32-byte Ed25519 private key seed used by {@code PouchService} in all
     * integration tests. Exposed so {@link com.dompetgaruda.api.wallet.PouchLoadTest} can
     * derive the matching public key for signature verification without overriding this property.
     */
    public static final String SIGNING_KEY_SEED = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

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
        registry.add("server.signing-key",          () -> SIGNING_KEY_SEED);
        // Production max per POUCH_MAX_AMOUNT_IDR.
        registry.add("pouch.max-amount-idr",        () -> 3_000_000L);
    }
}
