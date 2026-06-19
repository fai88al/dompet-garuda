package id.dompetdigital.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the Spring context against a real Postgres container (api profile),
 * confirms Flyway applied V1, and asserts key tables exist.
 */
@SpringBootTest
@ActiveProfiles("api")
@Testcontainers
class ScaffoldSmokeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("dompet")
            .withUsername("dompet")
            .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("SPRING_DATASOURCE_URL", postgres::getJdbcUrl);
        registry.add("SPRING_DATASOURCE_USERNAME", postgres::getUsername);
        registry.add("SPRING_DATASOURCE_PASSWORD", postgres::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliedV1AndKeyTablesExist() {
        // Verify that Flyway applied V1 by checking the flyway_schema_history table
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Integer.class);
        assertThat(migrationCount).isGreaterThanOrEqualTo(1);

        // Assert that the core ledger tables from V1__init.sql exist
        for (String table : new String[]{"users", "devices", "accounts",
                "ledger_transactions", "ledger_entries",
                "offline_certificates", "sync_inbox",
                "offline_transactions", "shedlock"}) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            assertThat(count).as("table '%s' should exist after V1 migration", table)
                    .isEqualTo(1);
        }
    }
}
