package com.dompetgaruda.api.device.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Backs {@link ValidEd25519PublicKey}. Blank values pass here — {@code @NotBlank} rejects those.
 *
 * <p>Mirrors {@link com.dompetgaruda.api.common.Ed25519Verifier}: a valid publicKey must be
 * Base64, and must decode to a well-formed X.509 SubjectPublicKeyInfo for the Ed25519
 * algorithm — the exact shape {@code Ed25519Verifier} reconstructs a {@code PublicKey} from
 * at signature-verification time. Anything else would be accepted here but fail every
 * offline-transaction signature check for that device.
 */
public class Ed25519PublicKeyValidator implements ConstraintValidator<ValidEd25519PublicKey, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(decoded));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
