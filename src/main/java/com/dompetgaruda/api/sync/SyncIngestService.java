package com.dompetgaruda.api.sync;

import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.sync.dto.SyncBatchRequest;
import com.dompetgaruda.api.sync.dto.SyncBatchResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Stores an uploaded offline transaction batch in {@code sync_inbox} and returns 202.
 *
 * <p>Intentionally thin — no signature verification, no ledger writes. Invariant 5
 * (CLAUDE.md §7): "API never posts to ledger from sync." The worker (PR7/PR8) handles
 * settlement after polling {@code sync_inbox}.
 *
 * <p>Flags {@code synced_after_expiry = true} when the batch arrives after the
 * referenced certificate's {@code expires_at}. Late syncs are accepted and stored;
 * the worker decides how to settle them. (CLAUDE.md §3 / FR12 / schema decision Q2.)
 */
@Service
@Profile("api")
public class SyncIngestService {

    private final JdbcTemplate  jdbc;
    private final ObjectMapper  objectMapper;

    public SyncIngestService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc         = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Stores {@code request} in {@code sync_inbox} and returns the assigned batch id.
     *
     * @param device  the authenticated sender device
     * @param request the batch payload as parsed from the request body
     * @return 202 response body with batch id and PENDING status
     */
    public SyncBatchResponse ingest(Device device, SyncBatchRequest request) {
        UUID batchId            = UUID.randomUUID();
        boolean afterExpiry     = isAfterExpiry(request.certificateId());
        String  rawPayload      = serialize(request);

        jdbc.update(
                "INSERT INTO sync_inbox (batch_id, device_id, raw_payload, status, synced_after_expiry) " +
                "VALUES (?, ?, ?::jsonb, 'PENDING', ?)",
                batchId,
                device.getDeviceId(),
                rawPayload,
                afterExpiry);

        return new SyncBatchResponse(batchId, "PENDING");
    }

    /**
     * Returns true when the certificate exists and its {@code expires_at} is in the past.
     * Missing certificate → false (worker will handle the invalid cert reference).
     */
    private boolean isAfterExpiry(UUID certificateId) {
        if (certificateId == null) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM offline_certificates " +
                "WHERE certificate_id = ? AND expires_at < now()",
                Integer.class,
                certificateId);
        return count != null && count > 0;
    }

    private String serialize(SyncBatchRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            // Should not happen — request was already parsed from valid JSON
            throw new IllegalStateException("Failed to re-serialize batch payload", e);
        }
    }
}
