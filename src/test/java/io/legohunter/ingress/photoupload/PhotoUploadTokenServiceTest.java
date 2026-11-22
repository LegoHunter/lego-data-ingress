package io.legohunter.ingress.photoupload;

import io.legohunter.data.dao.PhotoUploadTokenDao;
import io.legohunter.data.dto.PhotoUploadToken;
import io.legohunter.data.enums.PhotoUploadTokenKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The token rules of HOMELAB-134, against an in-memory table so the rotation is exercised end to end. */
class PhotoUploadTokenServiceTest {

    private static final Instant START = Instant.parse("2026-11-22T12:00:00Z");

    private final Map<String, PhotoUploadToken> rowsByHmac = new HashMap<>();
    private final Map<Long, PhotoUploadToken> rowsById = new HashMap<>();
    private long nextId = 1;
    private Instant now = START;
    private PhotoUploadTokenService service;
    private PhotoUploadTokenDao dao;

    @BeforeEach
    void setUp() {
        dao = mock(PhotoUploadTokenDao.class);
        when(dao.insert(any())).thenAnswer(invocation -> {
            PhotoUploadToken token = invocation.getArgument(0);
            token.setPhotoUploadTokenId(nextId++);
            rowsByHmac.put(token.getTokenHmac(), token);
            rowsById.put(token.getPhotoUploadTokenId(), token);
            return token;
        });
        when(dao.findByTokenHmac(any())).thenAnswer(invocation ->
                Optional.ofNullable(rowsByHmac.get((String) invocation.getArgument(0))));
        when(dao.retire(anyLong(), any())).thenAnswer(invocation -> {
            PhotoUploadToken row = rowsById.get((Long) invocation.getArgument(0));
            if (row.getRetiredAt() != null || row.getRevokedAt() != null) return false;
            row.setRetiredAt(ZonedDateTime.now(clock()));
            return true;
        });

        PhotoUploadProperties properties = new PhotoUploadProperties();
        properties.setEnvironment("sandbox");
        properties.setTokenSecret("test-secret-not-real");
        service = new PhotoUploadTokenService(dao, properties, clock());
    }

    /** A clock that reads the test's current time, so a test can move time forward after the service is built. */
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    private Clock clock() {
        return clock;
    }

    @Test
    void anIssuedRefreshTokenIsValidAndAnIssuedAccessTokenAuthenticates() {
        PhotoUploadTokenService.IssuedTokens issued = service.issue("owner-laptop");

        assertThat(service.status(issued.refreshToken())).isEqualTo(PhotoUploadTokenService.RefreshStatus.VALID);
        assertThat(service.authenticateAccess(issued.accessToken())).isPresent();
        assertThat(issued.accessExpiresInSeconds()).isEqualTo(3600);
        assertThat(issued.refreshExpiresInSeconds()).isEqualTo(43200);
    }

    @Test
    void refreshRotatesTheRefreshTokenAndRetiresTheOldOne() {
        PhotoUploadTokenService.IssuedTokens first = service.issue("owner-laptop");

        PhotoUploadTokenService.IssuedTokens second = service.refresh(first.refreshToken()).orElseThrow();

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(service.status(first.refreshToken())).isEqualTo(PhotoUploadTokenService.RefreshStatus.REVOKED);
        assertThat(service.status(second.refreshToken())).isEqualTo(PhotoUploadTokenService.RefreshStatus.VALID);
    }

    @Test
    void aRetiredRefreshTokenIsNeverAcceptedAgain() {
        PhotoUploadTokenService.IssuedTokens first = service.issue("owner-laptop");
        service.refresh(first.refreshToken()).orElseThrow();

        assertThat(service.refresh(first.refreshToken())).isEmpty();
    }

    @Test
    void theNewRefreshTokenStillWorksAfterTheOldOneWasReused() {
        PhotoUploadTokenService.IssuedTokens first = service.issue("owner-laptop");
        PhotoUploadTokenService.IssuedTokens second = service.refresh(first.refreshToken()).orElseThrow();
        assertThat(service.refresh(first.refreshToken())).isEmpty();

        // Reuse of the retired token is refused; this test pins that the family's newest token is unaffected
        // until the owner decides on family revocation (HOMELAB-134, open question).
        assertThat(service.status(second.refreshToken())).isEqualTo(PhotoUploadTokenService.RefreshStatus.VALID);
    }

    @Test
    void anExpiredRefreshTokenIsRefusedAndReportedExpired() {
        PhotoUploadTokenService.IssuedTokens issued = service.issue("owner-laptop");
        now = START.plusSeconds(43200);

        assertThat(service.status(issued.refreshToken())).isEqualTo(PhotoUploadTokenService.RefreshStatus.EXPIRED);
        assertThat(service.refresh(issued.refreshToken())).isEmpty();
    }

    @Test
    void anExpiredAccessTokenDoesNotAuthenticate() {
        PhotoUploadTokenService.IssuedTokens issued = service.issue("owner-laptop");
        now = START.plusSeconds(3600);

        assertThat(service.authenticateAccess(issued.accessToken())).isEmpty();
    }

    @Test
    void aRefreshTokenDoesNotAuthenticateAsAnAccessToken() {
        PhotoUploadTokenService.IssuedTokens issued = service.issue("owner-laptop");

        assertThat(service.authenticateAccess(issued.refreshToken())).isEmpty();
    }

    @Test
    void aTokenFromAnotherEnvironmentIsReportedRevoked() {
        PhotoUploadTokenService.IssuedTokens issued = service.issue("owner-laptop");
        rowsById.values().forEach(row -> row.setEnvironment("prod"));

        assertThat(service.status(issued.refreshToken())).isEqualTo(PhotoUploadTokenService.RefreshStatus.REVOKED);
        assertThat(service.authenticateAccess(issued.accessToken())).isEmpty();
    }

    @Test
    void unknownAndBlankTokensAreRevoked() {
        assertThat(service.status("not-a-token")).isEqualTo(PhotoUploadTokenService.RefreshStatus.REVOKED);
        assertThat(service.status(null)).isEqualTo(PhotoUploadTokenService.RefreshStatus.REVOKED);
        assertThat(service.refresh("  ")).isEmpty();
    }

    @Test
    void onlyHmacsAreStored() {
        PhotoUploadTokenService.IssuedTokens issued = service.issue("owner-laptop");

        ArgumentCaptor<PhotoUploadToken> captor = ArgumentCaptor.forClass(PhotoUploadToken.class);
        verify(dao, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        for (PhotoUploadToken row : captor.getAllValues()) {
            assertThat(row.getTokenHmac()).hasSize(64).isNotEqualTo(issued.refreshToken()).isNotEqualTo(issued.accessToken());
        }
    }

    @Test
    void aBlankSecretFailsClosed() {
        PhotoUploadProperties properties = new PhotoUploadProperties();
        properties.setEnvironment("sandbox");
        PhotoUploadTokenService unconfigured = new PhotoUploadTokenService(dao, properties, clock());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> unconfigured.authenticateAccess("x"))
                .isInstanceOf(IllegalStateException.class);
    }
}
