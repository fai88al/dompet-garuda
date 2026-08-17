package com.dompetgaruda.api.qrpayment;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Marks a single payment request EXPIRED in its own, independently-committed transaction
 * (CLAUDE.md §14.2 step f).
 *
 * <p>{@link PaymentRequestService#pay} detects real-time expiry, calls this, then throws a
 * 410 — which rolls back {@code pay()}'s own transaction. For the EXPIRED write to survive
 * that rollback it must run in a genuinely separate transaction, which Spring's proxy-based
 * {@code @Transactional} can only guarantee across a call to a <em>different</em> bean's
 * proxied method (a same-class self-invocation bypasses the proxy and would silently join
 * the caller's transaction instead of starting a new one) — hence this is its own bean.
 */
@Service
@Profile("api")
public class PaymentRequestExpiryWriter {

    private final JdbcTemplate jdbc;

    public PaymentRequestExpiryWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExpired(UUID requestId) {
        jdbc.update(
                "UPDATE payment_requests SET status = 'EXPIRED' WHERE request_id = ? AND status = 'PENDING'",
                requestId);
    }
}
