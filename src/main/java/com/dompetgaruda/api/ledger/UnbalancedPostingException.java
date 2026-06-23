package com.dompetgaruda.api.ledger;

/**
 * Thrown when a {@link PostingRequest} has total CREDIT ≠ total DEBIT.
 * Causes the enclosing {@code @Transactional} to roll back — no rows are written.
 */
public class UnbalancedPostingException extends RuntimeException {
    public UnbalancedPostingException(String message) {
        super(message);
    }
}
