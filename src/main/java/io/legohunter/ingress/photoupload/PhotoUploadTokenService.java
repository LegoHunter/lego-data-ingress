package io.legohunter.ingress.photoupload;

import io.legohunter.data.dao.PhotoUploadTokenDao;
import io.legohunter.data.dto.PhotoUploadToken;
import io.legohunter.data.enums.PhotoUploadTokenKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, rotates and checks the personal tokens of the Lightroom publisher (HOMELAB-134). A refresh token is
 * retired the moment it is exchanged and is never accepted again. Tokens are random; only their HMAC is stored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoUploadTokenService {

    public enum RefreshStatus { VALID, EXPIRED, REVOKED }

    public record IssuedTokens(String accessToken, long accessExpiresInSeconds,
                               String refreshToken, long refreshExpiresInSeconds) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PhotoUploadTokenDao photoUploadTokenDao;
    private final PhotoUploadProperties properties;
    private final Clock clock;

    /** Issues a new login: a refresh token and an access token in a new family. Called by the owner's admin path. */
    public IssuedTokens issue(String tokenName) {
        return issueInFamily(tokenName, UUID.randomUUID().toString());
    }

    /**
     * Exchanges a refresh token for a new pair. Returns empty when the token is unknown, expired, revoked, retired, or
     * belongs to another environment. Reuse of a retired token is refused and logged.
     */
    public Optional<IssuedTokens> refresh(String refreshToken) {
        Optional<PhotoUploadToken> row = findOwned(refreshToken, PhotoUploadTokenKind.REFRESH);
        if (row.isEmpty()) {
            log.info("photo_upload.refresh refused reason=unknown");
            return Optional.empty();
        }
        PhotoUploadToken token = row.get();
        ZonedDateTime now = ZonedDateTime.now(clock);
        if (token.getRevokedAt() != null) {
            log.info("photo_upload.refresh refused reason=revoked tokenName={}", token.getTokenName());
            return Optional.empty();
        }
        if (token.getRetiredAt() != null) {
            // A retired token presented again means a copy is in use: revoke the whole login (owner decision, HOMELAB-134).
            revokeFamilyOf(token, now, "retired-token-reused");
            return Optional.empty();
        }
        if (!now.isBefore(token.getExpiresAt())) {
            log.info("photo_upload.refresh refused reason=expired tokenName={}", token.getTokenName());
            return Optional.empty();
        }
        // Only the first caller to retire the token wins. A second use at the same moment is a reuse too.
        if (!photoUploadTokenDao.retire(token.getPhotoUploadTokenId(), now)) {
            revokeFamilyOf(token, now, "retired-concurrently");
            return Optional.empty();
        }
        log.info("photo_upload.refresh rotated tokenName={}", token.getTokenName());
        return Optional.of(issueInFamily(token.getTokenName(), token.getFamilyId()));
    }

    /** Revokes every live token issued to this name in this environment (the admin revoke). Returns the rows changed. */
    public int revokeByName(String tokenName) {
        return photoUploadTokenDao.revokeByName(properties.getEnvironment(), tokenName, ZonedDateTime.now(clock));
    }

    /** Reports whether a refresh token can still be exchanged. Does not rotate it. */
    public RefreshStatus status(String refreshToken) {
        return refreshDetails(refreshToken).state();
    }

    /** The state of a refresh token and, when it can still be used, the time it expires (HOMELAB-146). */
    public RefreshDetails refreshDetails(String refreshToken) {
        Optional<PhotoUploadToken> row = findOwned(refreshToken, PhotoUploadTokenKind.REFRESH);
        if (row.isEmpty()) return new RefreshDetails(RefreshStatus.REVOKED, null);
        PhotoUploadToken token = row.get();
        if (token.getRevokedAt() != null || token.getRetiredAt() != null) {
            return new RefreshDetails(RefreshStatus.REVOKED, null);
        }
        if (!ZonedDateTime.now(clock).isBefore(token.getExpiresAt())) {
            return new RefreshDetails(RefreshStatus.EXPIRED, null);
        }
        return new RefreshDetails(RefreshStatus.VALID, token.getExpiresAt());
    }

    /** The state of a refresh token; expiresAt is set only when it is valid. */
    public record RefreshDetails(RefreshStatus state, ZonedDateTime expiresAt) {
    }

    /** The access token's row when it is valid in this environment now, otherwise empty. */
    public Optional<PhotoUploadToken> authenticateAccess(String accessToken) {
        Optional<PhotoUploadToken> row = findOwned(accessToken, PhotoUploadTokenKind.ACCESS);
        if (row.isEmpty()) return Optional.empty();
        PhotoUploadToken token = row.get();
        if (token.getRevokedAt() != null) return Optional.empty();
        if (!ZonedDateTime.now(clock).isBefore(token.getExpiresAt())) return Optional.empty();
        return row;
    }

    /** Revokes every token of the login the given token belongs to, and logs why. */
    private void revokeFamilyOf(PhotoUploadToken token, ZonedDateTime now, String reason) {
        int revoked = photoUploadTokenDao.revokeFamily(token.getFamilyId(), now);
        log.warn("photo_upload.refresh refused reason={} tokenName={} revokedFamilyRows={}",
                reason, token.getTokenName(), revoked);
    }

    private IssuedTokens issueInFamily(String tokenName, String familyId) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        String refresh = newToken();
        String access = newToken();
        long refreshSeconds = properties.getRefreshTokenLifetime().toSeconds();
        long accessSeconds = properties.getAccessTokenLifetime().toSeconds();
        photoUploadTokenDao.insert(row(tokenName, PhotoUploadTokenKind.REFRESH, refresh, familyId,
                now.plusSeconds(refreshSeconds)));
        photoUploadTokenDao.insert(row(tokenName, PhotoUploadTokenKind.ACCESS, access, familyId,
                now.plusSeconds(accessSeconds)));
        return new IssuedTokens(access, accessSeconds, refresh, refreshSeconds);
    }

    private PhotoUploadToken row(String tokenName, PhotoUploadTokenKind kind, String token, String familyId,
                                 ZonedDateTime expiresAt) {
        return PhotoUploadToken.builder()
                .environment(properties.getEnvironment())
                .tokenName(tokenName)
                .tokenKind(kind)
                .tokenHmac(hasher().hash(token))
                .familyId(familyId)
                .expiresAt(expiresAt)
                .build();
    }

    private Optional<PhotoUploadToken> findOwned(String token, PhotoUploadTokenKind kind) {
        if (token == null || token.isBlank()) return Optional.empty();
        return photoUploadTokenDao.findByTokenHmac(hasher().hash(token))
                .filter(row -> row.getTokenKind() == kind)
                .filter(row -> properties.getEnvironment().equals(row.getEnvironment()));
    }

    private TokenHasher hasher() {
        return new TokenHasher(properties.getTokenSecret());
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
