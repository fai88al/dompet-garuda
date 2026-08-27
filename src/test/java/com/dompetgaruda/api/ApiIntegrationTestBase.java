package com.dompetgaruda.api;

import com.dompetgaruda.api.mqtt.MosquittoTestSupport;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
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

    protected static final UUID TEST_ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Shared MQTT admin credentials for {@link #mosquitto} — see {@link MosquittoTestSupport}. */
    public static final String MQTT_ADMIN_USERNAME = "dompet-api-admin";
    public static final String MQTT_ADMIN_PASSWORD = "test-admin-password";

    // Singleton containers — started once per JVM, shared by all subclasses.
    // @Container is intentionally absent: that annotation stops the container after each
    // test class, which breaks the shared Spring context. We start it here explicitly and
    // let the JVM exit handle cleanup (Testcontainers ryuk reaps it).
    protected static final PostgreSQLContainer<?> postgres = startPostgres();

    // Real eclipse-mosquitto:2 broker running the Dynamic Security plugin (CLAUDE.md §15).
    // AdminService now depends on MqttAdminClient (@Profile("api")), so every api-profile test
    // context needs a reachable broker just to construct that bean — this is it. Tests that
    // specifically exercise MQTT provisioning behaviour (MqttProvisioningTest) use their OWN
    // dedicated container instead, so they can freely stop/restart it without disrupting the
    // shared broker every other test class relies on.
    protected static final GenericContainer<?> mosquitto = startMosquitto();

    @SuppressWarnings("resource")
    private static PostgreSQLContainer<?> startPostgres() {
        PostgreSQLContainer<?> c = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("dompet")
                .withUsername("dompet")
                .withPassword("test");
        c.start();
        return c;
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> startMosquitto() {
        GenericContainer<?> c = MosquittoTestSupport.newContainer(MQTT_ADMIN_USERNAME, MQTT_ADMIN_PASSWORD);
        c.start();
        try {
            MosquittoTestSupport.createDeviceRole(
                    MosquittoTestSupport.brokerUrl(c), MQTT_ADMIN_USERNAME, MQTT_ADMIN_PASSWORD);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to bootstrap shared test Mosquitto broker", e);
        }
        return c;
    }

    @DynamicPropertySource
    static void baseProps(DynamicPropertyRegistry registry) {
        registry.add("SPRING_DATASOURCE_URL",      postgres::getJdbcUrl);
        registry.add("SPRING_DATASOURCE_USERNAME",  postgres::getUsername);
        registry.add("SPRING_DATASOURCE_PASSWORD",  postgres::getPassword);
        registry.add("server.signing-key",          () -> SIGNING_KEY_SEED);
        registry.add("pouch.max-amount-idr",        () -> 3_000_000L);
        registry.add("transfer.online.max-amount-idr", () -> 10_000_000L);
        registry.add("admin.jwt-secret",            () -> TEST_JWT_SECRET);
        registry.add("mqtt.broker-url",             () -> MosquittoTestSupport.brokerUrl(mosquitto));
        registry.add("mqtt.admin.username",         () -> MQTT_ADMIN_USERNAME);
        registry.add("mqtt.admin.password",         () -> MQTT_ADMIN_PASSWORD);
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
