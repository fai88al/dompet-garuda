package com.dompetgaruda.api.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for creating a new user.")
public record CreateUserRequest(
        @Schema(description = "Full legal name of the user.", example = "Budi Santoso", maxLength = 120)
        @NotBlank @Size(max = 120)
        String fullName,

        @Schema(description = "Mobile phone number in E.164 or local format.", example = "+62811000001", maxLength = 20)
        @NotBlank @Size(max = 20)
        @Pattern(regexp = "^\\+?[0-9]{8,20}$", message = "must be a valid phone number")
        String phone
) {}
