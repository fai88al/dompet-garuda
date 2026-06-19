package com.dompetgaruda.api.device;

import com.dompetgaruda.api.device.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * POST /admin/users
     * Creates a user and opens their ONLINE account. Admin-authenticated.
     */
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateUserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        return adminService.createUser(request);
    }

    /**
     * POST /admin/devices
     * Registers a device against an existing user. Returns the device token ONCE.
     * Admin-authenticated.
     */
    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterDeviceResponse registerDevice(@Valid @RequestBody RegisterDeviceRequest request) {
        return adminService.registerDevice(request);
    }
}
