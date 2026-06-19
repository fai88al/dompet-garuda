package com.dompetgaruda.api.device.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * deviceToken is the raw plaintext token returned ONCE at registration.
 * The caller must store it securely — it cannot be recovered after this response.
 * See CLAUDE.md §4.
 */
public record RegisterDeviceResponse(
        UUID deviceId,
        UUID userId,
        String deviceLabel,
        UUID pouchAccountId,
        Instant registeredAt,
        String deviceToken
) {}
