package com.dompetgaruda.api.device.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a field is a Base64-encoded Ed25519 public key in X.509 SubjectPublicKeyInfo
 * DER form — the format {@link com.dompetgaruda.api.common.Ed25519Verifier} and
 * {@code devices.public_key} already assume. Blank values are left to {@code @NotBlank}.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Ed25519PublicKeyValidator.class)
public @interface ValidEd25519PublicKey {

    String message() default "publicKey must be a valid Base64-encoded Ed25519 public key (X.509 SubjectPublicKeyInfo)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
