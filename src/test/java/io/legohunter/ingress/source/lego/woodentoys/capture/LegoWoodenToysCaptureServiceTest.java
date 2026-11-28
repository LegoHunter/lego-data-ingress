package io.legohunter.ingress.source.lego.woodentoys.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.legohunter.ingress.s3.api.S3Service;
import io.legohunter.ingress.source.lego.woodentoys.capture.LegoWoodenToysCaptureResult.Outcome;
import io.legohunter.ingress.source.lego.woodentoys.capture.LegoWoodenToysCatalogState.HeldEntry;
import io.legohunter.ingress.source.lego.woodentoys.capture.WoodenToysCaptureFile.Entry;
import io.legohunter.ingress.source.lego.woodentoys.capture.WoodenToysCaptureFile.Picture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.legohunter.ingress.source.lego.woodentoys.capture.CapturePages.entry;
import static io.legohunter.ingress.source.lego.woodentoys.capture.CapturePages.image;
import static io.legohunter.ingress.source.lego.woodentoys.capture.CapturePages.page;
import static io.legohunter.ingress.source.lego.woodentoys.capture.CapturePages.stockImage;
import static io.legohunter.ingress.source.lego.woodentoys.capture.CapturePages.url;
import static org.assertj.core.api.Assertions.assertThat;

class LegoWoodenToysCaptureServiceTest {
    private static final String BUCKET = "lego-uploads-sandbox";
    private static final Instant NOW = Instant.parse("2026-12-02T04:15:00Z");
    private static final String FILE_KEY = "lego/wooden-toys/catalog/20261202T041500Z.json";
    private static final Instant UPDATED = Instant.parse("2024-10-11T04:44:45.547Z");

    private final LegoWoodenToysCaptureProperties properties = new LegoWoodenToysCaptureProperties();
    private final FakeSource source = new FakeSource();
    private final FakeBucket bucket = new FakeBucket();
    private final FakeState state = new FakeState();
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private LegoWoodenToysCaptureService service;

    @BeforeEach
    void setUp() {
        service = new LegoWoodenToysCaptureService(properties, source, new LegoWoodenToysPageParser(properties),
                state, bucket, BUCKET, duration -> source.pauses++, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Three entries over two pages: a ferry with one picture, a duck with only the stock picture, a train with two. */
    private void threeEntries() {
        source.pages.put(1, page(3, List.of(
                entry("bltFerry", "330-ferry-1957", "330", 8, image("bltA", "ferry (1).jpg", 5)),
                entry("bltDuck", "17-duck-1935", "17", 2, stockImage()))));
        source.pages.put(2, page(3, List.of(
                entry("bltTrain", "410-train-1950", "410", 3, image("bltB", "train.jpg", 5), image("bltC", "group.png", 5)))));
        source.pictures.put(url("bltA", "ferry (1).jpg"), bytes("ferry"));
        source.pictures.put(url("bltB", "train.jpg"), bytes("train"));
        source.pictures.put(url("bltC", "group.png"), bytes("group"));
    }

    @Test
    void aFirstRunPutsThePicturesAndThenTheCaptureFile() throws IOException {
        threeEntries();

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.pagesRead()).isEqualTo(2);
        assertThat(result.reportedCount()).isEqualTo(3);
        assertThat(result.entriesCaptured()).isEqualTo(3);
        assertThat(result.entriesNew()).isEqualTo(3);
        assertThat(result.entriesChanged()).isEqualTo(3);
        assertThat(result.picturesListed()).isEqualTo(4);
        assertThat(result.stockPicturesRefused()).isEqualTo(1);
        assertThat(result.picturesToCapture()).isEqualTo(3);
        assertThat(result.picturesDownloaded()).isEqualTo(3);
        assertThat(result.bytesDownloaded()).isEqualTo(15);
        assertThat(result.pictureProblems()).isEmpty();
        assertThat(result.captureFileKey()).isEqualTo(FILE_KEY);

        assertThat(bucket.keysInOrder()).containsExactly(
                "lego/wooden-toys/images/bltFerry/bltA-ferry__1_.jpg",
                "lego/wooden-toys/images/bltTrain/bltB-train.jpg",
                "lego/wooden-toys/images/bltTrain/bltC-group.png",
                FILE_KEY);
        assertThat(bucket.contentTypes.get(FILE_KEY)).isEqualTo("application/json");
        assertThat(bucket.contentTypes.get("lego/wooden-toys/images/bltTrain/bltB-train.jpg")).isEqualTo("image/jpeg");
        assertThat(source.downloads).doesNotContain(url(CapturePages.STOCK_ID, "98765_image_on_its_way.jpg"));

        WoodenToysCaptureFile file = captureFile();
        assertThat(file.schemaVersion()).isEqualTo(1);
        assertThat(file.source()).isEqualTo("LEGO_COM");
        assertThat(file.capturedAt()).isEqualTo(NOW);
        assertThat(file.reportedCount()).isEqualTo(3);
        assertThat(file.entries()).extracting(Entry::slug)
                .containsExactly("330-ferry-1957", "17-duck-1935", "410-train-1950");
        assertThat(file.entries().get(1).pictures()).isEmpty();
        Picture ferry = file.entries().get(0).pictures().get(0);
        assertThat(ferry.objectKey()).isEqualTo("lego/wooden-toys/images/bltFerry/bltA-ferry__1_.jpg");
        assertThat(ferry.fileName()).isEqualTo("ferry (1).jpg");
        assertThat(ferry.byteSize()).isEqualTo(5L);
        assertThat(ferry.sha256()).isEqualTo(sha256(bytes("ferry")));
        assertThat(ferry.alreadyHeld()).isFalse();
        assertThat(file.entries().get(2).pictures()).extracting(Picture::sortOrder).containsExactly(0, 1);
        assertThat(bucket.objects.get(ferry.objectKey())).isEqualTo(bytes("ferry"));
    }

    @Test
    void aDryRunCountsWhatARunWouldDoAndWritesNothing() {
        threeEntries();

        LegoWoodenToysCaptureResult result = service.capture(false, false);

        assertThat(result.outcome()).isEqualTo(Outcome.DRY_RUN);
        assertThat(result.entriesChanged()).isEqualTo(3);
        assertThat(result.picturesToCapture()).isEqualTo(3);
        assertThat(result.picturesDownloaded()).isZero();
        assertThat(source.downloads).isEmpty();
        assertThat(bucket.objects).isEmpty();
    }

    @Test
    void aRunAfterTheLoadChangesNothingAndDownloadsNothing() {
        threeEntries();
        holdEverything();

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(result.entriesInDatabase()).isEqualTo(3);
        assertThat(result.entriesNew()).isZero();
        assertThat(result.entriesChanged()).isZero();
        assertThat(result.picturesAlreadyHeld()).isEqualTo(3);
        assertThat(result.captureFileKey()).isNull();
        assertThat(source.downloads).isEmpty();
        assertThat(bucket.objects).isEmpty();
    }

    @Test
    void aForcedRunWritesTheCaptureFileWithHeldPicturesAndNoBytes() throws IOException {
        threeEntries();
        holdEverything();

        LegoWoodenToysCaptureResult result = service.capture(true, true);

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(bucket.keysInOrder()).containsExactly(FILE_KEY);
        assertThat(source.downloads).isEmpty();
        Picture ferry = captureFile().entries().get(0).pictures().get(0);
        assertThat(ferry.alreadyHeld()).isTrue();
        assertThat(ferry.objectKey()).isNull();
        assertThat(ferry.sha256()).isNull();
        assertThat(ferry.sourceAssetId()).isEqualTo("bltA");
    }

    @Test
    void onlyTheNewPictureOfAChangedEntryIsDownloaded() throws IOException {
        threeEntries();
        holdEverything();
        state.entries.put("bltTrain", new HeldEntry(3, UPDATED, Set.of("bltB")));

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.entriesChanged()).isEqualTo(1);
        assertThat(source.downloads).containsExactly(url("bltC", "group.png"));
        List<Picture> train = captureFile().entries().get(2).pictures();
        assertThat(train).extracting(Picture::alreadyHeld).containsExactly(true, false);
        assertThat(train.get(1).objectKey()).isEqualTo("lego/wooden-toys/images/bltTrain/bltC-group.png");
    }

    @Test
    void anEntryCountsAsChangedWhenItsVersionDiffersOrAStoredPictureIsNoLongerListed() {
        threeEntries();
        holdEverything();
        state.entries.put("bltDuck", new HeldEntry(1, UPDATED, Set.of()));
        state.entries.put("bltFerry", new HeldEntry(8, UPDATED, Set.of("bltA", "bltGone")));

        LegoWoodenToysCaptureResult result = service.capture(false, false);

        assertThat(result.entriesChanged()).isEqualTo(2);
        assertThat(result.picturesToCapture()).isZero();
    }

    @Test
    void anEntryFoundByItsAddressWhenItsIdIsNotStoredIsNotNew() {
        threeEntries();
        holdEverything();
        state.entries.remove("bltDuck");
        state.entriesBySlug.put("17-duck-1935", new HeldEntry(2, UPDATED, Set.of()));

        assertThat(service.capture(true, false).outcome()).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void theStockPictureUnderANewIdIsRefusedByItsChecksum() throws IOException {
        threeEntries();
        properties.setStockPictureSha256(new ArrayList<>(List.of(sha256(bytes("group")).toUpperCase())));

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.stockPicturesRefused()).isEqualTo(2);
        assertThat(result.picturesDownloaded()).isEqualTo(2);
        assertThat(bucket.objects).doesNotContainKey("lego/wooden-toys/images/bltTrain/bltC-group.png");
        assertThat(captureFile().entries().get(2).pictures()).extracting(Picture::sourceAssetId).containsExactly("bltB");
    }

    @Test
    void aPictureOnMoreEntriesThanTheLimitIsReportedAndNotDownloaded() throws IOException {
        properties.setSharedPictureLimit(1);
        source.pages.put(1, page(2, List.of(
                entry("blt1", "one", "1", 1, image("bltNewStock", "coming_soon.jpg", 5)),
                entry("blt2", "two", "2", 1, image("bltNewStock", "coming_soon.jpg", 5), image("bltReal", "two.jpg", 5)))));
        source.pictures.put(url("bltReal", "two.jpg"), bytes("two"));

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.suspectSharedPictures())
                .containsExactly(new LegoWoodenToysCaptureResult.SharedPicture("bltNewStock", "coming_soon.jpg", 2));
        assertThat(source.downloads).containsExactly(url("bltReal", "two.jpg"));
        assertThat(captureFile().entries().get(0).pictures()).isEmpty();
    }

    @Test
    void aPictureAlreadyInTheBucketIsNotDownloadedAgain() throws IOException {
        threeEntries();
        bucket.putObject(BUCKET, "lego/wooden-toys/images/bltFerry/bltA-ferry__1_.jpg",
                new ByteArrayInputStream(bytes("ferry")), 5, "image/jpeg");

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.picturesReusedFromBucket()).isEqualTo(1);
        assertThat(result.picturesDownloaded()).isEqualTo(2);
        assertThat(source.downloads).doesNotContain(url("bltA", "ferry (1).jpg"));
        assertThat(captureFile().entries().get(0).pictures().get(0).sha256()).isEqualTo(sha256(bytes("ferry")));
    }

    @Test
    void aPictureTheSiteDoesNotHaveIsReportedAndTheEntryIsStillCaptured() throws IOException {
        threeEntries();
        source.pictures.remove(url("bltB", "train.jpg"));

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.pictureProblems()).hasSize(1);
        assertThat(result.pictureProblems().get(0)).contains("410-train-1950").contains("bltB");
        List<Picture> train = captureFile().entries().get(2).pictures();
        assertThat(train).extracting(Picture::sourceAssetId).containsExactly("bltC");
        assertThat(train).extracting(Picture::sortOrder).containsExactly(0);
    }

    @Test
    void aPictureWhoseSizeIsNotTheSizeLegoStatesIsKeptAndReported() throws IOException {
        threeEntries();
        source.pictures.put(url("bltB", "train.jpg"), bytes("a longer train"));

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.pictureProblems()).isEmpty();
        assertThat(result.pictureSizeDifferences()).hasSize(1);
        assertThat(result.pictureSizeDifferences().get(0))
                .contains("410-train-1950").contains("LEGO states 5 bytes, received 14");
        assertThat(captureFile().entries().get(2).pictures().get(0).byteSize()).isEqualTo(14L);
    }

    @Test
    void aPictureOverTheSizeLimitIsReportedAndNotDownloaded() {
        threeEntries();
        properties.setMaxPictureBytes(4);

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.pictureProblems()).hasSize(3);
        assertThat(source.downloads).isEmpty();
    }

    @Test
    void aDownloadThatKeepsFailingFailsTheRunBeforeTheCaptureFile() {
        threeEntries();
        source.unreachable.add(url("bltB", "train.jpg"));

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.captureFileKey()).isNull();
        assertThat(bucket.objects).doesNotContainKey(FILE_KEY);
        assertThat(bucket.keysInOrder()).containsExactly("lego/wooden-toys/images/bltFerry/bltA-ferry__1_.jpg");
    }

    @Test
    void aPageThatCannotBeReadStopsTheRunBeforeAnythingIsWritten() {
        threeEntries();
        source.pages.put(2, "<html>Just a moment...</html>");

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.STOPPED);
        assertThat(result.message()).contains("page 2");
        assertThat(bucket.objects).isEmpty();
        assertThat(source.downloads).isEmpty();
    }

    @Test
    void fewerEntriesThanTheSiteReportsStopsTheRun() {
        threeEntries();
        source.pages.put(2, page(3, List.of(entry("bltFerry", "330-ferry-1957", "330", 8))));

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.STOPPED);
        assertThat(result.message()).contains("read 2 distinct entries").contains("reports 3");
        assertThat(bucket.objects).isEmpty();
    }

    @Test
    void farFewerEntriesThanTheDatabaseHoldsStopsTheRun() {
        threeEntries();
        state.count = 10;

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.STOPPED);
        assertThat(result.message()).contains("read 3 entries").contains("holds 10");
        assertThat(bucket.objects).isEmpty();
    }

    @Test
    void aPageRequestThatFailsFailsTheRun() {
        threeEntries();
        source.pages.remove(2);

        LegoWoodenToysCaptureResult result = service.capture(true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(bucket.objects).isEmpty();
    }

    @Test
    void itWaitsBetweenPagesAndBetweenPictures() {
        threeEntries();

        service.capture(true, false);

        assertThat(source.pauses).isEqualTo(1 + 3);
    }

    private void holdEverything() {
        state.count = 3;
        state.entries.put("bltFerry", new HeldEntry(8, UPDATED, Set.of("bltA")));
        state.entries.put("bltDuck", new HeldEntry(2, UPDATED, Set.of()));
        state.entries.put("bltTrain", new HeldEntry(3, UPDATED, Set.of("bltB", "bltC")));
    }

    private WoodenToysCaptureFile captureFile() throws IOException {
        return mapper.readValue(bucket.objects.get(FILE_KEY), WoodenToysCaptureFile.class);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static class FakeSource implements LegoWoodenToysSource {
        final Map<Integer, String> pages = new HashMap<>();
        final Map<String, byte[]> pictures = new HashMap<>();
        final Set<String> unreachable = new HashSet<>();
        final List<String> downloads = new ArrayList<>();
        int pauses;

        @Override
        public String fetchPage(int page) {
            if (!pages.containsKey(page)) {
                throw new SourceException("page " + page + " failed after 3 tries: status 502", false, null);
            }
            return pages.get(page);
        }

        @Override
        public void downloadPicture(String url, Path target) {
            downloads.add(url);
            if (unreachable.contains(url)) {
                throw new SourceException(url + " failed after 3 tries: status 502", false, null);
            }
            if (!pictures.containsKey(url)) {
                throw new SourceException(url + " answered status 404", true, null);
            }
            try {
                Files.write(target, pictures.get(url));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static class FakeBucket implements S3Service {
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        final Map<String, String> contentTypes = new HashMap<>();

        List<String> keysInOrder() {
            return new ArrayList<>(objects.keySet());
        }

        @Override
        public InputStream getObject(String bucket, String key) {
            return new ByteArrayInputStream(objects.get(key));
        }

        @Override
        public boolean objectExists(String bucket, String key) {
            return objects.containsKey(key);
        }

        @Override
        public void putObject(String bucket, String key, InputStream stream, long size, String contentType) {
            assertThat(bucket).isEqualTo(BUCKET);
            try {
                byte[] bytes = stream.readAllBytes();
                assertThat((long) bytes.length).isEqualTo(size);
                objects.put(key, bytes);
                contentTypes.put(key, contentType);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void copyObject(String sourceBucket, String sourceKey, String destBucket, String destKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteObject(String bucket, String key) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FakeState implements LegoWoodenToysCatalogState {
        final Map<String, HeldEntry> entries = new HashMap<>();
        final Map<String, HeldEntry> entriesBySlug = new HashMap<>();
        int count;

        @Override
        public int capturedEntryCount() {
            return count;
        }

        @Override
        public Optional<HeldEntry> find(String uid, String slug) {
            return Optional.ofNullable(entries.get(uid)).or(() -> Optional.ofNullable(entriesBySlug.get(slug)));
        }
    }
}
