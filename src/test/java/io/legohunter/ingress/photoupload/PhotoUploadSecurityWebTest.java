package io.legohunter.ingress.photoupload;

import io.legohunter.ingress.config.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security rules of HOMELAB-134: presign needs an access token, the refresh and status endpoints do not, and
 * nothing outside /internal/photo-upload changes.
 */
@WebMvcTest(controllers = PhotoUploadController.class)
@Import(SecurityConfiguration.class)
class PhotoUploadSecurityWebTest {

    @Autowired MockMvc mvc;
    @MockBean PhotoUploadTokenService tokenService;
    @MockBean PhotoUploadPresignService presignService;
    @MockBean PhotoUploadStatusService statusService;

    private static final String PRESIGN_BODY = "{\"environment\":\"sandbox\",\"files\":[]}";

    @Test
    void presignWithoutATokenIsUnauthorised() throws Exception {
        mvc.perform(post("/internal/photo-upload/presign").contentType("application/json").content(PRESIGN_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void presignWithAnUnknownTokenIsUnauthorised() throws Exception {
        when(tokenService.authenticateAccess(anyString())).thenReturn(Optional.empty());
        mvc.perform(post("/internal/photo-upload/presign").contentType("application/json").content(PRESIGN_BODY)
                        .header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void presignWithAValidAccessTokenReachesTheService() throws Exception {
        when(tokenService.authenticateAccess(anyString()))
                .thenReturn(Optional.of(io.legohunter.data.dto.PhotoUploadToken.builder().tokenName("owner").build()));
        when(presignService.presign(any(), anyString()))
                .thenReturn(new PhotoUploadPresignService.PresignResponse("b1", java.util.List.of()));
        mvc.perform(post("/internal/photo-upload/presign").contentType("application/json").content(PRESIGN_BODY)
                        .header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk());
    }

    @Test
    void refreshIsOpenAtThisLayerAndRefusesAnUnknownToken() throws Exception {
        when(tokenService.refresh(any())).thenReturn(Optional.empty());
        mvc.perform(post("/internal/photo-upload/token/refresh").contentType("application/json")
                        .content("{\"refreshToken\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void statusIsOpenAtThisLayer() throws Exception {
        when(tokenService.refreshDetails(any())).thenReturn(new PhotoUploadTokenService.RefreshDetails(
                PhotoUploadTokenService.RefreshStatus.VALID, java.time.ZonedDateTime.parse("2026-11-24T01:00:00Z")));
        mvc.perform(get("/internal/photo-upload/token/status").header("Authorization", "Bearer x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("valid"))
                .andExpect(jsonPath("$.refreshExpiresAt").value("2026-11-24T01:00:00Z"));
    }

    @Test
    void otherPathsAreNotCaughtByThePhotoUploadChain() throws Exception {
        // Actuator endpoints are not part of this app's test slice, so they answer 404 here. The point is that
        // the photo-upload chain does not turn them into 401 or 403.
        int health = mvc.perform(get("/actuator/health/readiness")).andReturn().getResponse().getStatus();
        int liveness = mvc.perform(get("/actuator/health/liveness")).andReturn().getResponse().getStatus();
        int prometheus = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getStatus();
        assertThat(health).isNotIn(401, 403);
        assertThat(liveness).isNotIn(401, 403);
        assertThat(prometheus).isNotIn(401, 403);
    }
}
