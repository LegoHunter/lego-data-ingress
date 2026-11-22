package io.legohunter.ingress.photoupload;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** HMAC-SHA256 of a token, keyed with this environment's secret. Only the hash is stored (HOMELAB-134). */
final class TokenHasher {
    private final byte[] key;

    TokenHasher(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("lego.photo-upload.token-secret is not configured");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    String hash(String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
