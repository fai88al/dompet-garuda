package com.dompetgaruda.api.auth;

import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.common.repository.DeviceRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Verifies a device Bearer token by hashing it and looking up the matching device.
 *
 * The filter that calls this will be wired when the first device endpoint is built
 * (PR: sync-ingest). The verifier is built now so it is available at that point.
 *
 * Invariant: a device token proves "this call came from a provisioned device";
 * it does NOT authorize any specific money movement. See CLAUDE.md §4.
 */
@Component
public class DeviceTokenVerifier {

    private final DeviceRepository deviceRepository;
    private final DeviceTokenService deviceTokenService;

    public DeviceTokenVerifier(DeviceRepository deviceRepository,
                               DeviceTokenService deviceTokenService) {
        this.deviceRepository    = deviceRepository;
        this.deviceTokenService  = deviceTokenService;
    }

    /**
     * Returns the device associated with the raw token, or empty if the token
     * is invalid, not found, or the device is suspended/locked.
     */
    public Optional<Device> verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        try {
            String hash = deviceTokenService.hashToken(rawToken);
            return deviceRepository.findByDeviceTokenHash(hash)
                    .filter(d -> "ACTIVE".equals(d.getStatus()));
        } catch (IllegalArgumentException e) {
            // rawToken wasn't valid hex — treat as bad token
            return Optional.empty();
        }
    }
}
