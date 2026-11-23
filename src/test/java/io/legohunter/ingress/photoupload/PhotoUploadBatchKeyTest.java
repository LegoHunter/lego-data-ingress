package io.legohunter.ingress.photoupload;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoUploadBatchKeyTest {

    private static final String BATCH = "e9c0aafb-b942-4005-9b76-967adf796e52";

    @Test
    void anUploadKeyYieldsItsBatchAndFileName() {
        assertThat(PhotoUploadBatchKey.parse("photos/" + BATCH + "/IMG_1.jpg"))
                .contains(new PhotoUploadBatchKey(BATCH, "IMG_1.jpg"));
    }

    @Test
    void keysOutsideABatchYieldNothing() {
        assertThat(PhotoUploadBatchKey.parse("photos/rejected/" + BATCH + "/IMG_1.jpg")).isEmpty();
        assertThat(PhotoUploadBatchKey.parse("photos/not-a-batch/IMG_1.jpg")).isEmpty();
        assertThat(PhotoUploadBatchKey.parse("photos/" + BATCH + "/sub/IMG_1.jpg")).isEmpty();
        assertThat(PhotoUploadBatchKey.parse(null)).isEmpty();
    }
}
