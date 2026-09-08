package com.dompetgaruda.api.wallet;

import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.common.repository.DeviceRepository;
import com.dompetgaruda.api.mqtt.MqttPublisherService;
import com.dompetgaruda.api.notification.NotificationReconciliationService;
import com.dompetgaruda.api.wallet.dto.PouchLoadRequest;
import com.dompetgaruda.api.wallet.dto.PouchLoadResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Device-facing endpoint for FR3/FR13 — pouch provisioning (POST /device/pouch/load).
 *
 * <p>Identifies the caller via a {@code Device-Id} header, looked up directly against
 * {@code devices} (CLAUDE.md §1b, PRD R20) — no Bearer token, no signature check.
 * Loads funds from the user's ONLINE account into the device's POUCH and issues a
 * server-signed offline certificate.
 *
 * <p>{@code @Profile("api")} required: delegates to {@link PouchService} which is
 * also api-only (injects server.signing-key).
 */
@RestController
@RequestMapping("/device/pouch")
@Profile("api")
@Tag(name = "Device", description = "Device-facing endpoints — identify the caller with a Device-Id header.")
public class PouchController {

    private final DeviceRepository deviceRepository;
    private final PouchService        pouchService;
    private final NotificationReconciliationService notificationService;
    // Null in the api profile (MqttPublisherService is @Profile("worker")); optional cert-refresh hint
    @Autowired(required = false)
    private MqttPublisherService mqttPublisher;

    public PouchController(DeviceRepository deviceRepository, PouchService pouchService,
                            NotificationReconciliationService notificationService) {
        this.deviceRepository = deviceRepository;
        this.pouchService = pouchService;
        this.notificationService = notificationService;
    }

    @PostMapping("/load")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Load funds into offline pouch (FR3/FR13)",
            description = "Debits the user's ONLINE ledger account and credits the device's POUCH account. " +
                          "Issues a server-signed offline certificate the device uses to prove authorised spend. " +
                          "Fails with 409 if an ACTIVE certificate already exists for this device.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Pouch loaded; offline certificate issued."),
            @ApiResponse(responseCode = "400", description = "Validation failed — amount ≤ 0 or exceeds pouch max."),
            @ApiResponse(responseCode = "401", description = "Missing Device-Id header, or device not registered/not ACTIVE."),
            @ApiResponse(responseCode = "409", description = "Device already has an active offline certificate."),
            @ApiResponse(responseCode = "422", description = "Insufficient online balance.")
    })
    public PouchLoadResponse load(
            @Parameter(description = "Caller's device id (CLAUDE.md §1a). Required — identifies the device and its owning user.", required = true)
            @RequestHeader(name = "Device-Id", required = false) String deviceId,
            @Valid @RequestBody PouchLoadRequest request) {
        Device device = resolveDevice(deviceId);
        // Phase 3 Feature A (CLAUDE.md §17 point 4) — reconciliation piggybacks on this
        // authenticated hit rather than a new "I'm online now" endpoint. Best-effort, never
        // throws, never affects this response.
        notificationService.reconcileForDevice(device.getDeviceId());
        PouchLoadResponse response = pouchService.load(device, request);
        // @Transactional load() has committed; send cert-refresh hint (fire-and-forget, §7.8)
        if (mqttPublisher != null) {
            mqttPublisher.publishCertRefresh(device.getDeviceId().toString());
        }
        return response;
    }

    private Device resolveDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Device-Id header");
        }
        return deviceRepository.findById(deviceId)
                .filter(d -> "ACTIVE".equals(d.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid Device-Id"));
    }
}
