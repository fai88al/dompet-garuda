package com.dompetgaruda.api.qrpayment;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * FR22 — sweeps {@code payment_requests} rows past their {@code expires_at} and marks them
 * EXPIRED, independent of {@code PaymentRequestService.pay()}'s own real-time expiry check
 * (belt-and-suspenders, CLAUDE.md §14.2). Never posts to the ledger — status-only.
 *
 * <p>{@code @Profile("worker")} — never loaded in the api container (§3 profile isolation rule).
 * {@code @SchedulerLock} — §7 invariant 7: all @Scheduled methods must have ShedLock.
 */
@Component
@Profile("worker")
public class PaymentRequestExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestExpiryJob.class);

    private final JdbcTemplate jdbc;

    public PaymentRequestExpiryJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "payment-request-expiry", lockAtMostFor = "PT50S", lockAtLeastFor = "PT5S")
    public void expirePaymentRequests() {
        sweep();
    }

    /**
     * Sweep logic, separated from the scheduling/locking entry point so tests can call it
     * directly (mirrors {@code PouchReconciliationJob.doReconcile()}).
     */
    int sweep() {
        int updated = jdbc.update(
                "UPDATE payment_requests SET status = 'EXPIRED' " +
                "WHERE status = 'PENDING' AND expires_at < now()");
        log.info("Payment request expiry sweep complete. Expired: {}", updated);
        return updated;
    }
}
