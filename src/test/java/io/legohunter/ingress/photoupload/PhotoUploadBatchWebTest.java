package io.legohunter.ingress.photoupload;

import io.legohunter.ingress.config.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The batch status endpoint: it needs a valid access token (HOMELAB-135). */
@WebMvcTest(controllers = PhotoUploadController.class)
@Import(SecurityConfiguration.class)
class PhotoUploadBatchWebTest {

    @Autowired MockMvc mvc;
    @MockBean PhotoUploadTokenService tokenService;
    @MockBean PhotoUploadPresignService presignService;
    @MockBean PhotoUploadStatusService statusService;

    private static final String BATCH = "e9c0aafb-b942-4005-9b76-967adf796e52";

    @Test
    void theStatusNeedsAnAccessToken() throws Exception {
        mvc.perform(get("/internal/photo-upload/batches/" + BATCH)).andExpect(status().isUnauthorized());
    }

    @Test
    void theStatusReturnsEachFileWithItsState() throws Exception {
        when(tokenService.authenticateAccess(anyString()))
                .thenReturn(Optional.of(io.legohunter.data.dto.PhotoUploadToken.builder().tokenName("owner").build()));
        when(statusService.status(BATCH)).thenReturn(Optional.of(new PhotoUploadStatusService.BatchStatus(BATCH, true,
                List.of(new PhotoUploadStatusService.FileStatus("a.jpg", "rejected", "missing uuid")))));

        mvc.perform(get("/internal/photo-upload/batches/" + BATCH).header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.files[0].state").value("rejected"))
                .andExpect(jsonPath("$.files[0].reason").value("missing uuid"));
    }

    @Test
    void anUnknownBatchIsNotFound() throws Exception {
        when(tokenService.authenticateAccess(anyString()))
                .thenReturn(Optional.of(io.legohunter.data.dto.PhotoUploadToken.builder().tokenName("owner").build()));
        when(statusService.status(BATCH)).thenReturn(Optional.empty());

        mvc.perform(get("/internal/photo-upload/batches/" + BATCH).header("Authorization", "Bearer good"))
                .andExpect(status().isNotFound());
    }
}
