package com.dompetgaruda.api.common;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Stateless Ed25519 signature verifier. Profile-agnostic — usable in both api and worker.
 *
 * <p>Public keys are stored in {@code devices.public_key} as Base64(X.509 DER).
 * Signatures are Base64(raw 64-byte Ed25519 signature).
 *
 * <p>Never throws on a bad signature or malformed key — returns {@code false} instead.
 * This ensures the caller cannot be tricked into treating a verification exception as success.
 */
@Component
public class Ed25519Verifier {

    /**
     * Verifies an Ed25519 signature.
     *
     * @param message        the exact string that was signed (UTF-8 encoded before signing)
     * @param base64Sig      Base64-encoded raw 64-byte Ed25519 signature
     * @param base64PubKey   Base64-encoded X.509 DER public key (as stored in devices.public_key)
     * @return {@code true} if the signature is valid; {@code false} for any failure
     */
    public boolean verify(String message, String base64Sig, String base64PubKey) {
        try {
            byte[] pubKeyBytes = Base64.getDecoder().decode(base64PubKey);
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(pubKeyBytes));

            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(base64Sig));
        } catch (Exception e) {
            return false;
        }
    }
}
