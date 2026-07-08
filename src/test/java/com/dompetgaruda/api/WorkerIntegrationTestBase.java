package com.dompetgaruda.api;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared base class for all worker-profile integration tests.
 *
 * <p>Mirrors {@link ApiIntegrationTestBase} but activates the {@code worker} profile and
 * disables the web server ({@code webEnvironment = NONE}). The worker profile has no REST
 * endpoints, no admin token filter, and no Flyway — Flyway runs in the api container first,
 * so the schema is already in place when the worker starts.
 *
 * <p>Only the datasource properties are required in this context: all beans that inject
 * {@code admin.api-token}, {@code server.signing-key}, or {@code pouch.max-amount-idr} are
 * annotated {@code @Profile("api")} and are never loaded by the worker context.
 *
 * <p>Subclasses may add their own {@code @DynamicPropertySource} for test-specific overrides,
 * but must not override the datasource keys set here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("worker")
@Testcontainers
public abstract class WorkerIntegrationTestBase {

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
        // In production the api container runs Flyway before the worker starts (depends_on).
        // In tests there is no api container, so we enable Flyway here to initialise the schema.
        registry.add("spring.flyway.enabled",       () -> "true");
    }
}
