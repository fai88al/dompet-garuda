package com.dompetgaruda.api.device;

import com.dompetgaruda.api.auth.DeviceTokenService;
import com.dompetgaruda.api.common.entity.Account;
import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.common.entity.User;
import com.dompetgaruda.api.common.repository.AccountRepository;
import com.dompetgaruda.api.common.repository.DeviceRepository;
import com.dompetgaruda.api.common.repository.UserRepository;
import com.dompetgaruda.api.device.dto.*;
import com.dompetgaruda.api.mqtt.MqttAdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/**
 * {@code @Profile("api")}: depends on {@link MqttAdminClient}, which is itself api-profile-only
 * (CLAUDE.md §3 / §15) — without this annotation the worker profile would fail to start with
 * {@code NoSuchBeanDefinitionException} for {@code MqttAdminClient}.
 */
@Service
@Profile("api")
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    // Decision R3 (PRD §9): max 3 devices per user.
    private static final int MAX_DEVICES_PER_USER = 3;

    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final AccountRepository accountRepository;
    private final DeviceTokenService deviceTokenService;
    private final MqttAdminClient mqttAdminClient;

    public AdminService(UserRepository userRepository,
                        DeviceRepository deviceRepository,
                        AccountRepository accountRepository,
                        DeviceTokenService deviceTokenService,
                        MqttAdminClient mqttAdminClient) {
        this.userRepository    = userRepository;
        this.deviceRepository  = deviceRepository;
        this.accountRepository = accountRepository;
        this.deviceTokenService = deviceTokenService;
        this.mqttAdminClient   = mqttAdminClient;
    }

    /**
     * Creates a user and opens their ONLINE ledger account in one transaction.
     * No money is posted — balance is zero until the first top-up.
     */
    @Transactional
    public CreateUserResponse createUser(CreateUserRequest req) {
        if (userRepository.existsByPhone(req.phone())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A user with this phone number already exists");
        }

        User user = new User();
        user.setFullName(req.fullName());
        user.setPhone(req.phone());
        userRepository.save(user);

        Account online = new Account();
        online.setUserId(user.getUserId());
        online.setType("ONLINE");
        accountRepository.save(online);

        return new CreateUserResponse(
                user.getUserId(),
                user.getFullName(),
                user.getPhone(),
                user.getStatus(),
                online.getAccountId(),
                user.getCreatedAt());
    }

    private static final Set<String> VALID_STATUSES = Set.of("ACTIVE", "SUSPENDED", "LOCKED");

    /**
     * Updates a device's status (FR17).
     * Application-level validation returns 400 before hitting the DB CHECK constraint.
     * JPA @PreUpdate on Device sets updatedAt automatically.
     */
    @Transactional
    public UpdateDeviceStatusResponse updateDeviceStatus(String deviceId, UpdateDeviceStatusRequest req) {
        if (!VALID_STATUSES.contains(req.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid status: " + req.status() + ". Must be one of: ACTIVE, SUSPENDED, LOCKED");
        }

        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Device not found: " + deviceId));

        device.setStatus(req.status());
        deviceRepository.save(device);

        return new UpdateDeviceStatusResponse(device.getDeviceId(), device.getStatus(), device.getUpdatedAt());
    }

    /**
     * Best-effort MQTT ACL sync after a device status change has already committed (FR26/R14).
     * Deliberately NOT {@code @Transactional} and NOT called from inside {@link #updateDeviceStatus}
     * — it must run strictly after the status row commits, and must never roll back or fail the
     * status change. Swallows every exception and logs a WARNING instead: an emergency suspend of
     * a lost/stolen device must never be blocked by an unrelated MQTT/broker outage.
     */
    public void syncMqttAccess(String deviceId, String newStatus) {
        try {
            if ("ACTIVE".equals(newStatus)) {
                mqttAdminClient.reinstateDevice(deviceId);
            } else {
                mqttAdminClient.revokeDevice(deviceId);
            }
        } catch (Exception e) {
            log.warn("MQTT access sync failed for device {} (new status {}): {}",
                    deviceId, newStatus, e.getMessage());
        }
    }

    /**
     * Registers a device against an existing user.
     * Enforces: max 3 devices per user (FR1 / Decision R3) and unique public key (FR1).
     * Generates a device API token, stores only its SHA-256 hash (CLAUDE.md §4 / §7.9).
     * Also opens a POUCH account for the device (needed for future pouch provisioning).
     */
    @Transactional
    public RegisterDeviceResponse registerDevice(RegisterDeviceRequest req) {
        User user = userRepository.findById(req.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User not found: " + req.userId()));

        if (deviceRepository.countByUserId(user.getUserId()) >= MAX_DEVICES_PER_USER) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "User already has the maximum of " + MAX_DEVICES_PER_USER + " devices");
        }

        if (deviceRepository.existsByPublicKey(req.publicKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A device with this public key is already registered");
        }

        // FR27/§1a: deviceId is now hardware-sourced, not server-generated, so it is a
        // client-supplied primary key. @Pattern on RegisterDeviceRequest already rejects
        // '/' and '|' with 400 before this method runs; this existence check is the clean
        // 409 for a reused id, ahead of the DB CHECK constraint / PK violation.
        if (deviceRepository.existsById(req.deviceId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A device with this deviceId is already registered: " + req.deviceId());
        }

        DeviceTokenService.TokenPair tokenPair = deviceTokenService.generate();

        Device device = new Device();
        device.setDeviceId(req.deviceId());
        device.setUserId(user.getUserId());
        device.setPublicKey(req.publicKey());
        device.setDeviceLabel(req.label());
        device.setDeviceTokenHash(tokenPair.hash());
        deviceRepository.save(device);

        // Mandatory, not best-effort (CLAUDE.md §15 / FR25): a failure here throws
        // MqttProvisioningException (unchecked), which rolls back this entire transaction —
        // the device row above is never persisted. Caught at the controller and mapped to 503.
        mqttAdminClient.provisionDevice(device.getDeviceId(), tokenPair.token());

        Account pouch = new Account();
        pouch.setUserId(user.getUserId());
        pouch.setDeviceId(device.getDeviceId());
        pouch.setType("POUCH");
        accountRepository.save(pouch);

        // tokenPair.token() is returned once here and never stored or logged.
        return new RegisterDeviceResponse(
                device.getDeviceId(),
                user.getUserId(),
                device.getDeviceLabel(),
                pouch.getAccountId(),
                device.getRegisteredAt(),
                tokenPair.token());
    }
}
