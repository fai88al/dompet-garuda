package com.dompetgaruda.api.device;

import com.dompetgaruda.api.auth.DeviceTokenService;
import com.dompetgaruda.api.common.entity.Account;
import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.common.entity.User;
import com.dompetgaruda.api.common.repository.AccountRepository;
import com.dompetgaruda.api.common.repository.DeviceRepository;
import com.dompetgaruda.api.common.repository.UserRepository;
import com.dompetgaruda.api.device.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminService {

    // Decision R3 (PRD §9): max 3 devices per user.
    private static final int MAX_DEVICES_PER_USER = 3;

    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final AccountRepository accountRepository;
    private final DeviceTokenService deviceTokenService;

    public AdminService(UserRepository userRepository,
                        DeviceRepository deviceRepository,
                        AccountRepository accountRepository,
                        DeviceTokenService deviceTokenService) {
        this.userRepository    = userRepository;
        this.deviceRepository  = deviceRepository;
        this.accountRepository = accountRepository;
        this.deviceTokenService = deviceTokenService;
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

        DeviceTokenService.TokenPair tokenPair = deviceTokenService.generate();

        Device device = new Device();
        device.setUserId(user.getUserId());
        device.setPublicKey(req.publicKey());
        device.setDeviceLabel(req.label());
        device.setDeviceTokenHash(tokenPair.hash());
        deviceRepository.save(device);

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
