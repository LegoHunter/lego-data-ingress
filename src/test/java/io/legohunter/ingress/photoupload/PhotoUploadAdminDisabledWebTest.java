package io.legohunter.ingress.photoupload;

import io.legohunter.ingress.config.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With no admin key configured the admin path is off: every call answers 404 (HOMELAB-139). */
@WebMvcTest(controllers = PhotoUploadAdminController.class)
@Import({SecurityConfiguration.class, PhotoUploadConfiguration.class})
@TestPropertySource(properties = {"lego.photo-upload.environment=sandbox", "lego.photo-upload.admin-key="})
class PhotoUploadAdminDisabledWebTest {

    @Autowired MockMvc mvc;
    @MockBean PhotoUploadTokenService tokenService;

    @Test
    void theAdminPathAnswersNotFoundWhenNoKeyIsConfigured() throws Exception {
        mvc.perform(post("/internal/photo-upload/admin/tokens").contentType("application/json")
                        .content("{\"tokenName\":\"owner-laptop\"}")
                        .header("X-Admin-Key", "anything"))
                .andExpect(status().isNotFound());
    }
}
