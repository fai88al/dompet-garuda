package com.dompetgaruda.api.device;

import com.dompetgaruda.api.device.dto.*;
import com.dompetgaruda.api.mqtt.MqttProvisioningException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** {@code @Profile("api")}: depends on {@link AdminService}, itself api-profile-only (see there). */
@RestController
@RequestMapping("/admin")
@Profile("api")
@Tag(name = "Admin", description = "Admin-only endpoints for user and device provisioning. Require Bearer token in Authorization header.")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a user and open their online ledger account.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "User created; online account opened."),
        @ApiResponse(responseCode = "400", description = "Validation failure — missing or malformed field."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid admin Bearer token."),
        @ApiResponse(responseCode = "409", description = "Phone number already registered.")
    })
    public CreateUserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        return adminService.createUser(request);
    }

    @PostMapping("/devices")
    @Operation(summary = "Register an ESP32 device against an existing user. Returns the device Bearer token once — it cannot be recovered.",
            description = "As of FR25, also provisions the device's MQTT credentials (same device token, reused as the MQTT password) " +
                          "as a mandatory part of registration. A provisioning failure rolls back the entire registration.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Device registered; device token returned once; MQTT credentials provisioned."),
        @ApiResponse(responseCode = "400", description = "Validation failure — missing or malformed field."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid admin Bearer token."),
        @ApiResponse(responseCode = "404", description = "User not found."),
        @ApiResponse(responseCode = "409", description = "Public key already registered to another device."),
        @ApiResponse(responseCode = "422", description = "User already has the maximum number of devices (3)."),
        @ApiResponse(responseCode = "503", description = "MQTT credential provisioning failed — registration rolled back, nothing persisted.")
    })
    public ResponseEntity<RegisterDeviceResponse> registerDevice(@Valid @RequestBody RegisterDeviceRequest request) {
        try {
            RegisterDeviceResponse response = adminService.registerDevice(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (MqttProvisioningException e) {
            log.warn("Device registration rolled back — MQTT provisioning failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Device registration is temporarily unavailable. Please try again.");
        }
    }

    @PatchMapping("/devices/{deviceId}/status")
    @Operation(
            summary = "Update device status (FR17).",
            description = "Sets device status to ACTIVE, SUSPENDED, or LOCKED. " +
                          "A SUSPENDED or LOCKED device will fail Bearer token verification on all device endpoints. " +
                          "As of FR26, also revokes (SUSPENDED/LOCKED) or reinstates (ACTIVE) the device's MQTT access, " +
                          "best-effort — an MQTT-side failure never blocks or changes this endpoint's response.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status updated."),
        @ApiResponse(responseCode = "400", description = "Invalid status value."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid admin Bearer token."),
        @ApiResponse(responseCode = "404", description = "Device not found.")
    })
    public ResponseEntity<UpdateDeviceStatusResponse> updateDeviceStatus(
            @Parameter(description = "Device UUID.") @PathVariable UUID deviceId,
            @Valid @RequestBody UpdateDeviceStatusRequest request) {
        UpdateDeviceStatusResponse response = adminService.updateDeviceStatus(deviceId, request);
        adminService.syncMqttAccess(deviceId, response.status());
        return ResponseEntity.ok(response);
    }
}
