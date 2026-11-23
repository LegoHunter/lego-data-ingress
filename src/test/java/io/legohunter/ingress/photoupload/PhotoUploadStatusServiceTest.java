package io.legohunter.ingress.photoupload;

import io.legohunter.data.dao.PhotoUploadBatchDao;
import io.legohunter.data.dto.PhotoUploadBatch;
import io.legohunter.data.dto.PhotoUploadBatchFile;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The state rules of the batch status endpoint (HOMELAB-135, upload-design section 13). */
class PhotoUploadStatusServiceTest {

    private static final String BATCH = "e9c0aafb-b942-4005-9b76-967adf796e52";
    private static final Instant CREATED = Instant.parse("2026-11-22T12:00:00Z");

    private final PhotoUploadBatchDao dao = mock(PhotoUploadBatchDao.class);
    private Instant now = CREATED;
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final PhotoUploadStatusService service;

    PhotoUploadStatusServiceTest() {
        PhotoUploadProperties properties = new PhotoUploadProperties();
        properties.setEnvironment("sandbox");
        properties.setStatusFailedAfter(Duration.ofMinutes(15));
        service = new PhotoUploadStatusService(dao, properties, clock);
    }

    private void batch(String environment, List<PhotoUploadBatchFile> files) {
        when(dao.findBatch(BATCH)).thenReturn(Optional.of(PhotoUploadBatch.builder().batchId(BATCH)
                .environment(environment).tokenName("owner-laptop")
                .createdAt(ZonedDateTime.ofInstant(CREATED, ZoneOffset.UTC)).build()));
        when(dao.findFiles(BATCH)).thenReturn(files);
    }

    private static PhotoUploadBatchFile file(String name, String state, String reason) {
        return PhotoUploadBatchFile.builder().batchId(BATCH).fileName(name).state(state).reason(reason).build();
    }

    @Test
    void anUnknownBatchIsEmpty() {
        when(dao.findBatch(BATCH)).thenReturn(Optional.empty());
        assertThat(service.status(BATCH)).isEmpty();
    }

    @Test
    void aBatchFromAnotherEnvironmentIsEmpty() {
        batch("prod", List.of(file("a.jpg", "accepted", null)));
        assertThat(service.status(BATCH)).isEmpty();
    }

    @Test
    void eachOutcomeIsReportedWithItsReason() {
        batch("sandbox", List.of(
                file("a.jpg", "accepted", null),
                file("b.jpg", "rejected", "Photo metadata is missing required uuid"),
                file("c.jpg", "duplicate", null)));

        PhotoUploadStatusService.BatchStatus status = service.status(BATCH).orElseThrow();

        assertThat(status.complete()).isTrue();
        assertThat(status.files()).containsExactly(
                new PhotoUploadStatusService.FileStatus("a.jpg", "accepted", null),
                new PhotoUploadStatusService.FileStatus("b.jpg", "rejected", "Photo metadata is missing required uuid"),
                new PhotoUploadStatusService.FileStatus("c.jpg", "duplicate", null));
    }

    @Test
    void aPendingFileInsideTheWindowIsPendingAndTheBatchIsNotComplete() {
        now = CREATED.plus(Duration.ofMinutes(5));
        batch("sandbox", List.of(file("a.jpg", "accepted", null), file("b.jpg", "pending", null)));

        PhotoUploadStatusService.BatchStatus status = service.status(BATCH).orElseThrow();

        assertThat(status.complete()).isFalse();
        assertThat(status.files().get(1).state()).isEqualTo("pending");
    }

    @Test
    void aPendingFileAfterTheWindowIsFailed() {
        now = CREATED.plus(Duration.ofMinutes(16));
        batch("sandbox", List.of(file("b.jpg", "pending", null)));

        PhotoUploadStatusService.BatchStatus status = service.status(BATCH).orElseThrow();

        assertThat(status.files().get(0).state()).isEqualTo("failed");
        assertThat(status.complete()).isTrue();
    }
}
