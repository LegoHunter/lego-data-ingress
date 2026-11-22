package io.legohunter.ingress.photoupload;

import io.legohunter.ingress.photoupload.PhotoUploadController.TokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Admin path for the Lightroom publisher's tokens (HOMELAB-139). Called by the owner from Bruno with the admin key in
 * X-Admin-Key. With a blank admin key the path answers 404, so it is off until the secret is set.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/photo-upload/admin")
public class PhotoUploadAdminController {

    public record IssueRequest(String tokenName) {
    }

    public record RevokeRequest(String tokenName) {
    }

    public record RevokeResponse(int revokedRows) {
    }

    private static final Pattern TOKEN_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private final PhotoUploadTokenService tokenService;
    private final PhotoUploadProperties properties;

    @PostMapping("/tokens")
    public ResponseEntity<TokenResponse> issue(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                               @RequestBody(required = false) IssueRequest request) {
        requireAdmin(key);
        String name = validName(request == null ? null : request.tokenName());
        PhotoUploadTokenService.IssuedTokens issued = tokenService.issue(name);
        log.info("photo_upload.admin action=issue environment={} tokenName={}", properties.getEnvironment(), name);
        return ResponseEntity.ok(new TokenResponse(issued.accessToken(), issued.accessExpiresInSeconds(),
                issued.refreshToken(), issued.refreshExpiresInSeconds()));
    }

    /** Revokes every live token issued to the name in this environment. Refresh tokens stop working at once. */
    @PostMapping("/tokens/revoke")
    public ResponseEntity<RevokeResponse> revoke(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                 @RequestBody(required = false) RevokeRequest request) {
        requireAdmin(key);
        String name = validName(request == null ? null : request.tokenName());
        int rows = tokenService.revokeByName(name);
        log.info("photo_upload.admin action=revoke environment={} tokenName={} revokedRows={}",
                properties.getEnvironment(), name, rows);
        return ResponseEntity.ok(new RevokeResponse(rows));
    }

    @ExceptionHandler(PhotoUploadRefusedException.class)
    public ResponseEntity<Map<String, String>> refused(PhotoUploadRefusedException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    private void requireAdmin(String key) {
        String expected = properties.getAdminKey();
        if (expected == null || expected.isBlank()) {
            throw new PhotoUploadRefusedException(HttpStatus.NOT_FOUND, "not found");
        }
        boolean matches = key != null && MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            log.warn("photo_upload.admin refused reason=bad-admin-key");
            throw new PhotoUploadRefusedException(HttpStatus.UNAUTHORIZED, "the admin key is missing or wrong");
        }
    }

    private static String validName(String name) {
        if (name == null || !TOKEN_NAME.matcher(name).matches()) {
            throw new PhotoUploadRefusedException(HttpStatus.BAD_REQUEST,
                    "tokenName must be 1 to 100 letters, digits, dots, dashes or underscores");
        }
        return name;
    }
}
