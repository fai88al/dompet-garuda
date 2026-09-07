package com.dompetgaruda.api.notification;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly worker sweep that expires stale PENDING notifications (Phase 3 Feature A, CLAUDE.md
 * §17 point 5) — belt-and-suspenders alongside the reconcile-on-reconnect check in
 * {@link NotificationReconciliationService#reconcileForDevice}, for a receiver device that
 * never reconnects within the expiry window.
 *
 * <p>Purely a notification-delivery-status update — never touches the ledger (§7 invariant 1).
 *
 * <p>{@code @Profile("worker")} — never loaded in the api container (§3 profile isolation rule).
 * {@code @SchedulerLock} — §7 invariant 7: all @Scheduled methods must have ShedLock.
 */
@Component
@Profile("worker")
public class NotificationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationExpiryJob.class);

    private final JdbcTemplate jdbc;

    public NotificationExpiryJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // initialDelay defers the first automatic run past worker startup, mirroring
    // PouchReconciliationJob — an hourly sweep gains nothing from running immediately at boot.
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    @SchedulerLock(name = "notification-expiry-job", lockAtMostFor = "PT55M", lockAtLeastFor = "PT30S")
    public void expire() {
        doExpire();
    }

    /**
     * Expiry logic, separated from the scheduling/locking entry point so tests can call it
     * directly without depending on ShedLock behaviour (mirrors {@code PouchReconciliationJob}).
     */
    void doExpire() {
        int expired = jdbc.update(
                "UPDATE notification_log SET status = 'EXPIRED' " +
                "WHERE status = 'PENDING' AND expires_at < now()");
        log.info("Notification expiry sweep complete. Expired: {}", expired);
    }
}
