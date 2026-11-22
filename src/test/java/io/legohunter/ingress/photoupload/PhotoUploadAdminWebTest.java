package io.legohunter.ingress.photoupload;

import io.legohunter.ingress.config.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin path with the admin key set (HOMELAB-139). */
@WebMvcTest(controllers = PhotoUploadAdminController.class)
@Import({SecurityConfiguration.class, PhotoUploadConfiguration.class})
@org.springframework.test.context.TestPropertySource(properties = {
        "lego.photo-upload.environment=sandbox",
        "lego.photo-upload.admin-key=test-admin-key-for-the-web-test"})
class PhotoUploadAdminWebTest {

    @Autowired MockMvc mvc;
    @MockBean PhotoUploadTokenService tokenService;

    private static final String ISSUE = "/internal/photo-upload/admin/tokens";
    private static final String REVOKE = "/internal/photo-upload/admin/tokens/revoke";
    private static final String BODY = "{\"tokenName\":\"owner-laptop\"}";

    @Test
    void issueWithTheRightKeyReturnsTheTokens() throws Exception {
        when(tokenService.issue("owner-laptop")).thenReturn(
                new PhotoUploadTokenService.IssuedTokens("access-x", 3600, "refresh-x", 43200));
        mvc.perform(post(ISSUE).contentType("application/json").content(BODY)
                        .header("X-Admin-Key", "test-admin-key-for-the-web-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("refresh-x"))
                .andExpect(jsonPath("$.accessToken").value("access-x"));
    }

    @Test
    void aMissingOrWrongKeyIsUnauthorisedAndIssuesNothing() throws Exception {
        mvc.perform(post(ISSUE).contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ISSUE).contentType("application/json").content(BODY)
                        .header("X-Admin-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(tokenService);
    }

    @Test
    void aBadTokenNameIsBadRequest() throws Exception {
        mvc.perform(post(ISSUE).contentType("application/json").content("{\"tokenName\":\"../x\"}")
                        .header("X-Admin-Key", "test-admin-key-for-the-web-test"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void revokeReportsHowManyTokensChanged() throws Exception {
        when(tokenService.revokeByName(anyString())).thenReturn(2);
        mvc.perform(post(REVOKE).contentType("application/json").content(BODY)
                        .header("X-Admin-Key", "test-admin-key-for-the-web-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedRows").value(2));
    }

    @Test
    void theAdminPathIsNotPublicWithoutTheKey() throws Exception {
        mvc.perform(post(REVOKE).contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(tokenService, org.mockito.Mockito.never()).revokeByName(any());
    }
}
