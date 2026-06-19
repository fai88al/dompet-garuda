package com.dompetgaruda.api.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 120)
        String fullName,

        @NotBlank @Size(max = 20)
        @Pattern(regexp = "^\\+?[0-9]{8,20}$", message = "must be a valid phone number")
        String phone
) {}
