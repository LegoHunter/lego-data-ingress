package io.legohunter.ingress.photoupload;

import io.legohunter.ingress.photoupload.PhotoUploadPresignService.FileRequest;
import io.legohunter.ingress.photoupload.PhotoUploadPresignService.PresignRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each refusal of the presign endpoint (HOMELAB-134). These run before any call to Silo. */
class PhotoUploadPresignServiceTest {

    private PhotoUploadPresignService service(String environment) {
        PhotoUploadProperties properties = new PhotoUploadProperties();
        properties.setEnvironment(environment);
        return new PhotoUploadPresignService(properties, new io.legohunter.ingress.config.storage.S3ClientProperties(), org.mockito.Mockito.mock(io.legohunter.data.dao.PhotoUploadBatchDao.class));
    }

    private static FileRequest jpg(String name) {
        return new FileRequest(name, 1_000L, "image/jpeg");
    }

    private static void assertRefused(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(PhotoUploadRefusedException.class,
                        e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void aRequestForAnotherEnvironmentIsForbidden() {
        PhotoUploadPresignService service = service("sandbox");
        assertRefused(() -> service.presign(new PresignRequest("dev", List.of(jpg("a.jpg")), null), "t"),
                HttpStatus.FORBIDDEN);
    }

    @Test
    void aMissingEnvironmentIsForbidden() {
        PhotoUploadPresignService service = service("sandbox");
        assertRefused(() -> service.presign(new PresignRequest(null, List.of(jpg("a.jpg")), null), "t"),
                HttpStatus.FORBIDDEN);
    }

    @Test
    void aPRODUploadWithoutConfirmationIsForbidden() {
        PhotoUploadPresignService service = service("prod");
        assertRefused(() -> service.presign(new PresignRequest("prod", List.of(jpg("a.jpg")), null), "t"),
                HttpStatus.FORBIDDEN);
        assertRefused(() -> service.presign(new PresignRequest("prod", List.of(jpg("a.jpg")), "yes"), "t"),
                HttpStatus.FORBIDDEN);
    }

    @Test
    void aSandboxUploadDoesNotNeedPRODConfirmation() {
        // Passes validation; with no Silo URL it reaches the configuration check, not the PROD check.
        PhotoUploadPresignService service = service("sandbox");
        assertRefused(() -> service.presign(new PresignRequest("sandbox", List.of(jpg("a.jpg")), null), "t"),
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void noFilesIsBadRequest() {
        PhotoUploadPresignService service = service("sandbox");
        assertRefused(() -> service.presign(new PresignRequest("sandbox", List.of(), null), "t"),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void moreThanTwentyFiveFilesIsBadRequest() {
        PhotoUploadPresignService service = service("sandbox");
        List<FileRequest> files = java.util.stream.IntStream.rangeClosed(1, 26)
                .mapToObj(i -> jpg("p" + i + ".jpg")).toList();
        assertRefused(() -> service.presign(new PresignRequest("sandbox", files, null), "t"),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void aNonJpegNameIsBadRequest() {
        PhotoUploadPresignService service = service("sandbox");
        assertRefused(() -> service.presign(new PresignRequest("sandbox", List.of(jpg("a.png")), null), "t"),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void aFolderPartInTheNameIsBadRequest() {
        PhotoUploadPresignService service = service("sandbox");
        for (String name : List.of("../a.jpg", "x/a.jpg", "x\\a.jpg")) {
            assertRefused(() -> service.presign(new PresignRequest("sandbox", List.of(jpg(name)), null), "t"),
                    HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void anOversizeOrEmptyFileIsBadRequest() {
        PhotoUploadPresignService service = service("sandbox");
        assertRefused(() -> service.presign(new PresignRequest("sandbox",
                        List.of(new FileRequest("a.jpg", 10_000_001L, "image/jpeg")), null), "t"),
                HttpStatus.BAD_REQUEST);
        assertRefused(() -> service.presign(new PresignRequest("sandbox",
                        List.of(new FileRequest("a.jpg", 0L, "image/jpeg")), null), "t"),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void aWrongContentTypeIsBadRequest() {
        PhotoUploadPresignService service = service("sandbox");
        assertRefused(() -> service.presign(new PresignRequest("sandbox",
                        List.of(new FileRequest("a.jpg", 100L, "image/png")), null), "t"),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void aRepeatedNameIsBadRequest() {
        PhotoUploadPresignService service = service("sandbox");
        assertRefused(() -> service.presign(new PresignRequest("sandbox",
                        List.of(jpg("a.jpg"), jpg("a.jpg")), null), "t"),
                HttpStatus.BAD_REQUEST);
    }
}
