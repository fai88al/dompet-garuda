package com.dompetgaruda.api;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Generates Base64-encoded Ed25519 public keys (X.509 SubjectPublicKeyInfo DER) for tests —
 * the format RegisterDeviceRequest.publicKey requires (see ValidEd25519PublicKey) and that
 * Ed25519Verifier already assumes when reconstructing a PublicKey to check signatures.
 */
public final class Ed25519TestSupport {

    private Ed25519TestSupport() {}

    /** A real, distinct Ed25519 public key each call — valid X.509 DER, Base64-encoded. */
    public static String randomValidPublicKeyBase64() {
        return realEd25519PublicKeyBase64();
    }

    /**
     * Generates a real Ed25519 public key. The {@code seed} parameter exists only so call
     * sites can pass a human-readable label (e.g. "pk-transfer-001") for readability — it has
     * no bearing on the generated key, which is always freshly and genuinely random.
     */
    public static String derivePublicKeyBase64(String seed) {
        return realEd25519PublicKeyBase64();
    }

    /** A real Ed25519 public key, generated via the JDK's Ed25519 KeyPairGenerator. */
    public static String realEd25519PublicKeyBase64() {
        try {
            KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 KeyPairGenerator not available", e);
        }
    }
}
