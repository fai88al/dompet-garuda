package com.dompetgaruda.api;

import java.util.UUID;

/**
 * Generates test deviceId values in the new hardware-sourced string format (CLAUDE.md §1a) —
 * a 12-character uppercase hex string, deliberately NOT UUID-shaped, so tests exercise the
 * post-migration format rather than accidentally continuing to pass UUID-looking strings.
 */
public final class DeviceIdTestSupport {

    private DeviceIdTestSupport() {}

    public static String randomDeviceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
