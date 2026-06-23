package com.dompetgaruda.api.device;

import com.dompetgaruda.api.device.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
@Tag(name = "Admin", description = "Admin-only endpoints for user and device provisioning. Require Bearer token in Authorization header.")
public class AdminController {

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
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register an ESP32 device against an existing user. Returns the device Bearer token once — it cannot be recovered.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Device registered; device token returned once."),
        @ApiResponse(responseCode = "400", description = "Validation failure — missing or malformed field."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid admin Bearer token."),
        @ApiResponse(responseCode = "404", description = "User not found."),
        @ApiResponse(responseCode = "409", description = "Public key already registered to another device."),
        @ApiResponse(responseCode = "422", description = "User already has the maximum number of devices (3).")
    })
    public RegisterDeviceResponse registerDevice(@Valid @RequestBody RegisterDeviceRequest request) {
        return adminService.registerDevice(request);
    }
}
