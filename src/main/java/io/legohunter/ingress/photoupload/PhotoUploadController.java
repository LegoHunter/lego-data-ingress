package io.legohunter.ingress.photoupload;

import io.legohunter.ingress.photoupload.PhotoUploadPresignService.PresignRequest;
import io.legohunter.ingress.photoupload.PhotoUploadPresignService.PresignResponse;
import io.legohunter.ingress.photoupload.PhotoUploadTokenService.IssuedTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.security.Principal;

/** The Lightroom publisher's upload endpoints (HOMELAB-134). See the security chain for who may call each one. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/photo-upload")
public class PhotoUploadController {

    public record RefreshRequest(String refreshToken) {
    }

    public record TokenResponse(String accessToken, long accessExpiresInSeconds,
                                String refreshToken, long refreshExpiresInSeconds) {
    }

    private final PhotoUploadTokenService tokenService;
    private final PhotoUploadPresignService presignService;
    private final PhotoUploadStatusService statusService;

    @PostMapping("/presign")
    public ResponseEntity<PresignResponse> presign(@RequestBody PresignRequest request, Principal principal) {
        return ResponseEntity.ok(presignService.presign(request, principal.getName()));
    }

    /** Exchanges a refresh token for a new pair. The refresh token is retired at once. */
    @PostMapping("/token/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody RefreshRequest request) {
        Optional<IssuedTokens> issued = tokenService.refresh(request == null ? null : request.refreshToken());
        return issued
                .map(tokens -> ResponseEntity.ok(toResponse(tokens)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    /** Reports whether the refresh token in the Authorization header can still be exchanged. Does not rotate it. */
    /** The token's state, and when a valid token expires (HOMELAB-146). Does not rotate the token. */
    @GetMapping("/token/status")
    public TokenStatusResponse status(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim()
                : null;
        PhotoUploadTokenService.RefreshDetails details = tokenService.refreshDetails(token);
        return new TokenStatusResponse(
                details.state().name().toLowerCase(java.util.Locale.ROOT),
                details.expiresAt() == null ? null : details.expiresAt().toInstant().toString());
    }

    /** state is valid, expired or revoked; refreshExpiresAt is an ISO-8601 time when valid, otherwise null. */
    public record TokenStatusResponse(String state, String refreshExpiresAt) {
    }

    /** The state of each file in a batch (HOMELAB-135). Needs a valid access token. */
    @GetMapping("/batches/{batchId}")
    public ResponseEntity<PhotoUploadStatusService.BatchStatus> batch(@PathVariable String batchId) {
        return statusService.status(batchId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @ExceptionHandler(PhotoUploadRefusedException.class)
    public ResponseEntity<Map<String, String>> refused(PhotoUploadRefusedException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    private static TokenResponse toResponse(IssuedTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.accessExpiresInSeconds(),
                tokens.refreshToken(), tokens.refreshExpiresInSeconds());
    }
}
