package com.dompetgaruda.api;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Shared base class for all api-profile integration tests.
 *
 * <p>Provides a single Testcontainers Postgres instance and a {@code @DynamicPropertySource}
 * that wires the datasource, {@code server.signing-key}, {@code pouch.max-amount-idr}, and
 * {@code admin.jwt-secret} with fixed test values so all subclasses share one Spring context.
 *
 * <p>{@link #SIGNING_KEY_SEED} is the Ed25519 seed configured for {@code server.signing-key}.
 * {@link com.dompetgaruda.api.wallet.PouchLoadTest} derives its verification keypair from this
 * constant. No subclass should override {@code server.signing-key}.
 *
 * <p>{@link #testAdminJwt()} issues a valid admin JWT signed with {@link #TEST_JWT_SECRET}
 * for use as a Bearer token in test requests. Subclasses call this instead of hitting the
 * login endpoint so tests remain independent of login-endpoint behaviour.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
public abstract class ApiIntegrationTestBase {

    /**
     * Base64-encoded 32-byte Ed25519 private key seed used by {@code PouchService} in all
     * integration tests. Exposed so {@link com.dompetgaruda.api.wallet.PouchLoadTest} can
     * derive the matching public key for signature verification without overriding this property.
     */
    public static final String SIGNING_KEY_SEED = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    /** 64-hex-char (32-byte) HMAC-SHA256 key used to sign test admin JWTs. */
    public static final String TEST_JWT_SECRET =
            "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";

    private static final UUID TEST_ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // Singleton container — started once per JVM, shared by all subclasses.
    // @Container is intentionally absent: that annotation stops the container after each
    // test class, which breaks the shared Spring context. We start it here explicitly and
    // let the JVM exit handle cleanup (Testcontainers ryuk reaps it).
    protected static final PostgreSQLContainer<?> postgres = startPostgres();

    @SuppressWarnings("resource")
    private static PostgreSQLContainer<?> startPostgres() {
        PostgreSQLContainer<?> c = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("dompet")
                .withUsername("dompet")
                .withPassword("test");
        c.start();
        return c;
    }

    @DynamicPropertySource
    static void baseProps(DynamicPropertyRegistry registry) {
        registry.add("SPRING_DATASOURCE_URL",      postgres::getJdbcUrl);
        registry.add("SPRING_DATASOURCE_USERNAME",  postgres::getUsername);
        registry.add("SPRING_DATASOURCE_PASSWORD",  postgres::getPassword);
        registry.add("server.signing-key",          () -> SIGNING_KEY_SEED);
        registry.add("pouch.max-amount-idr",        () -> 3_000_000L);
        registry.add("admin.jwt-secret",            () -> TEST_JWT_SECRET);
    }

    /**
     * Issues a valid admin JWT signed with {@link #TEST_JWT_SECRET}, valid for 24 h.
     * Use as {@code headers.setBearerAuth(testAdminJwt())} in test requests.
     */
    public static String testAdminJwt() {
        SecretKey key = Keys.hmacShaKeyFor(HexFormat.of().parseHex(TEST_JWT_SECRET));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(TEST_ADMIN_ID.toString())
                .claim("username", "test-admin@dompetgaruda.com")
                .claim("role", "ADMIN")
                .issuedAt(new Date(now))
                .expiration(new Date(now + 24L * 60 * 60 * 1000))
                .signWith(key)
                .compact();
    }
}
