package com.dompetgaruda.api.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rate limiter for the admin login endpoint (FR15).
 *
 * <p>After {@value #MAX_ATTEMPTS} consecutive failed attempts from the same IP address within
 * a {@value #WINDOW_MINUTES}-minute window, all subsequent attempts from that IP return 429
 * until the window expires.
 *
 * <p>A successful login resets the counter for that IP.
 *
 * <p>This is a prototype-grade implementation (CLAUDE.md §4 / "sufficient for the prototype").
 * It is not shared across JVM instances, does not persist across restarts, and uses
 * {@code getRemoteAddr()} / {@code X-Forwarded-For} for the IP. A production system would use
 * Redis or a persistent store.
 *
 * <p>{@code @Profile("api")} — only loaded in the API container.
 */
@Component
@Profile("api")
public class LoginAttemptTracker {

    static final int MAX_ATTEMPTS     = 5;
    static final int WINDOW_MINUTES   = 5;

    private static final Duration WINDOW = Duration.ofMinutes(WINDOW_MINUTES);

    private final ConcurrentHashMap<String, AttemptRecord> state = new ConcurrentHashMap<>();

    /**
     * Returns {@code true} when the given IP has reached the maximum failed-attempt threshold
     * and is still within its rate-limit window.
     */
    public boolean isBlocked(String ip) {
        AttemptRecord rec = state.get(ip);
        if (rec == null) return false;
        if (Instant.now().isAfter(rec.windowEnd())) {
            state.remove(ip);
            return false;
        }
        return rec.count() >= MAX_ATTEMPTS;
    }

    /**
     * Records one failed login attempt from {@code ip}.
     * Opens a new window on the first failure; increments within an existing window.
     */
    public void recordFailure(String ip) {
        state.compute(ip, (key, rec) -> {
            Instant now = Instant.now();
            if (rec == null || now.isAfter(rec.windowEnd())) {
                return new AttemptRecord(1, now.plus(WINDOW));
            }
            return new AttemptRecord(rec.count() + 1, rec.windowEnd());
        });
    }

    /** Resets the failure counter for {@code ip} after a successful login. */
    public void recordSuccess(String ip) {
        state.remove(ip);
    }

    /** Clears all recorded state. Used in integration tests to ensure isolation between tests. */
    public void clearAll() {
        state.clear();
    }

    private record AttemptRecord(int count, Instant windowEnd) {}
}
