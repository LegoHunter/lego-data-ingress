package io.legohunter.ingress.source.lego.woodentoys.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.legohunter.ingress.s3.api.S3Service;
import io.legohunter.ingress.source.lego.woodentoys.capture.LegoWoodenToysCaptureResult.Outcome;
import io.legohunter.ingress.source.lego.woodentoys.capture.LegoWoodenToysCaptureResult.SharedPicture;
import io.legohunter.ingress.source.lego.woodentoys.capture.LegoWoodenToysCatalogState.HeldEntry;
import io.legohunter.ingress.source.lego.woodentoys.capture.LegoWoodenToysPageParser.PageFormatException;
import io.legohunter.ingress.source.lego.woodentoys.capture.LegoWoodenToysSource.SourceException;
import io.legohunter.ingress.source.lego.woodentoys.capture.WoodenToysCaptureFile.Entry;
import io.legohunter.ingress.source.lego.woodentoys.capture.WoodenToysCaptureFile.Picture;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Captures LEGO's wooden toys catalog from lego.com into the uploads bucket.
 *
 * <p>A run reads every overview page, checks the result, downloads the pictures the database does not hold yet, and
 * then writes one capture file ({@link WoodenToysCaptureFile}). The pictures go first and the capture file last,
 * because the file arriving in the bucket is what starts the load. The capture never writes to the database.
 *
 * <ul>
 *   <li>LEGO's stock "image on its way" picture is never kept: it is refused by its id before download and by its
 *       checksum after. A picture attached to more entries than {@code sharedPictureLimit} is taken for a new
 *       stock picture, reported and not downloaded.</li>
 *   <li>A page that cannot be read, a count that does not match, or far fewer entries than the database holds
 *       stops the run before anything is written.</li>
 *   <li>A picture the site answers "not found" for, or one over the size limit, is reported and left out; the
 *       entry is still captured. A server error or connection failure that outlasts the retries fails the run
 *       before the capture file is written. Pictures already put in the bucket stay there and are reused by the
 *       next run.</li>
 *   <li>When nothing differs from the database, no capture file is written, unless {@code force} is set.</li>
 * </ul>
 */
@Slf4j
public class LegoWoodenToysCaptureService {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String JSON = "application/json";
    private static final String BINARY = "application/octet-stream";

    private final LegoWoodenToysCaptureProperties properties;
    private final LegoWoodenToysSource source;
    private final LegoWoodenToysPageParser parser;
    private final LegoWoodenToysCatalogState catalogState;
    private final S3Service s3Service;
    private final String uploadsBucket;
    private final Pauser pauser;
    private final Clock clock;
    private final ObjectMapper fileMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    public LegoWoodenToysCaptureService(
            LegoWoodenToysCaptureProperties properties,
            LegoWoodenToysSource source,
            LegoWoodenToysPageParser parser,
            LegoWoodenToysCatalogState catalogState,
            S3Service s3Service,
            String uploadsBucket,
            Pauser pauser,
            Clock clock
    ) {
        this.properties = properties;
        this.source = source;
        this.parser = parser;
        this.catalogState = catalogState;
        this.s3Service = s3Service;
        this.uploadsBucket = uploadsBucket;
        this.pauser = pauser;
        this.clock = clock;
    }

    /**
     * Runs one capture.
     *
     * @param apply false for a dry run: lego.com and the database are read, nothing is downloaded or written
     * @param force write a capture file even when nothing differs from the database
     */
    public LegoWoodenToysCaptureResult capture(boolean apply, boolean force) {
        Tally tally = new Tally(apply, clock.instant());
        try {
            run(tally, force);
        } catch (PageFormatException e) {
            tally.end(Outcome.STOPPED, e.getMessage());
        } catch (Stop e) {
            tally.end(Outcome.STOPPED, e.getMessage());
        } catch (RuntimeException e) {
            log.error("lego_wooden_toys.capture.failed", e);
            tally.end(Outcome.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        LegoWoodenToysCaptureResult result = tally.result(clock.instant());
        if (result.outcome().failure()) {
            log.warn("lego_wooden_toys.capture.result {}", result);
        } else {
            log.info("lego_wooden_toys.capture.result {}", result);
        }
        return result;
    }

    private void run(Tally tally, boolean force) {
        List<Entry> listed = readCatalog(tally);

        tally.entriesInDatabase = catalogState.capturedEntryCount();
        if (listed.size() < properties.getMinimumEntryRatio() * tally.entriesInDatabase) {
            throw new Stop("read %d entries, but the database holds %d captured entries"
                    .formatted(listed.size(), tally.entriesInDatabase));
        }

        Set<String> stockIds = new HashSet<>(properties.getStockPictureAssetIds());
        Set<String> suspectIds = suspectSharedPictures(listed, stockIds, tally);

        // Plan: which pictures are already held, and which entries differ from the database.
        List<Entry> planned = new ArrayList<>();
        for (Entry entry : listed) {
            Optional<HeldEntry> held = catalogState.find(entry.uid(), entry.slug());
            Set<String> heldPictures = held.map(HeldEntry::legoPictureAssetIds).orElse(Set.of());
            List<Picture> pictures = new ArrayList<>();
            boolean pictureToCapture = false;
            for (Picture picture : entry.pictures()) {
                tally.picturesListed++;
                if (stockIds.contains(picture.sourceAssetId())) {
                    tally.stockPicturesRefused++;
                } else if (suspectIds.contains(picture.sourceAssetId())) {
                    // reported once, in suspectSharedPictures
                } else if (heldPictures.contains(picture.sourceAssetId())) {
                    tally.picturesAlreadyHeld++;
                    pictures.add(picture.held());
                } else {
                    tally.picturesToCapture++;
                    pictureToCapture = true;
                    pictures.add(picture);
                }
            }
            if (held.isEmpty()) {
                tally.entriesNew++;
            }
            if (held.isEmpty() || pictureToCapture || differs(entry, held.get(), pictures)) {
                tally.entriesChanged++;
            }
            planned.add(entry.withPictures(pictures));
        }

        if (!tally.apply) {
            tally.end(Outcome.DRY_RUN, "");
            return;
        }
        if (tally.entriesChanged == 0 && !force) {
            tally.end(Outcome.UNCHANGED, "");
            return;
        }

        // Pictures first, one at a time.
        Set<String> stockChecksums = new HashSet<>();
        properties.getStockPictureSha256().forEach(checksum -> stockChecksums.add(checksum.toLowerCase()));
        List<Entry> captured = new ArrayList<>();
        for (Entry entry : planned) {
            List<Picture> pictures = new ArrayList<>();
            for (Picture picture : entry.pictures()) {
                if (picture.alreadyHeld()) {
                    pictures.add(picture);
                } else {
                    capturePicture(entry, picture, stockChecksums, tally).ifPresent(pictures::add);
                }
            }
            captured.add(entry.withPictures(renumbered(pictures)));
        }

        // The capture file last: its arrival starts the load.
        String key = properties.getObjectPrefix() + "/catalog/" + FILE_TIME.format(tally.startedAt) + ".json";
        WoodenToysCaptureFile file = new WoodenToysCaptureFile(WoodenToysCaptureFile.SCHEMA_VERSION,
                WoodenToysCaptureFile.SOURCE, tally.startedAt, tally.reportedCount, captured);
        byte[] bytes;
        try {
            bytes = fileMapper.writeValueAsBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("the capture file could not be written as JSON", e);
        }
        s3Service.putObject(uploadsBucket, key, new ByteArrayInputStream(bytes), bytes.length, JSON);
        tally.captureFileKey = key;
        tally.end(Outcome.COMPLETED, "");
    }

    /** Reads every overview page and checks that the entries are exactly the catalog the site reports. */
    private List<Entry> readCatalog(Tally tally) {
        LegoWoodenToysPageParser.Page first = parser.parse(source.fetchPage(1));
        tally.pagesRead = 1;
        tally.reportedCount = first.reportedCount();
        if (first.entries().isEmpty() || first.reportedCount() <= 0) {
            throw new Stop("the first page lists no entries");
        }
        int pages = (first.reportedCount() + first.entries().size() - 1) / first.entries().size();
        if (pages > properties.getMaxPages()) {
            throw new Stop("the catalog reports %d pages, more than the limit of %d"
                    .formatted(pages, properties.getMaxPages()));
        }
        Map<String, Entry> byUid = new LinkedHashMap<>();
        first.entries().forEach(entry -> byUid.put(entry.uid(), entry));
        for (int page = 2; page <= pages; page++) {
            pauser.pause(properties.getPagePause());
            LegoWoodenToysPageParser.Page next;
            try {
                next = parser.parse(source.fetchPage(page));
            } catch (PageFormatException e) {
                throw new PageFormatException("page " + page + ": " + e.getMessage(), e);
            }
            tally.pagesRead = page;
            next.entries().forEach(entry -> byUid.put(entry.uid(), entry));
        }
        tally.entriesCaptured = byUid.size();
        if (byUid.size() != first.reportedCount()) {
            throw new Stop("read %d distinct entries from %d pages, but the site reports %d"
                    .formatted(byUid.size(), pages, first.reportedCount()));
        }
        Set<String> slugs = new HashSet<>();
        for (Entry entry : byUid.values()) {
            if (!slugs.add(entry.slug())) {
                throw new Stop("two entries share the address " + entry.slug());
            }
        }
        return new ArrayList<>(byUid.values());
    }

    /** Pictures attached to more entries than the limit that are not known as the stock picture. */
    private Set<String> suspectSharedPictures(List<Entry> entries, Set<String> stockIds, Tally tally) {
        Map<String, Integer> uses = new HashMap<>();
        Map<String, String> fileNames = new HashMap<>();
        for (Entry entry : entries) {
            Set<String> seen = new HashSet<>();
            for (Picture picture : entry.pictures()) {
                if (seen.add(picture.sourceAssetId())) {
                    uses.merge(picture.sourceAssetId(), 1, Integer::sum);
                    fileNames.putIfAbsent(picture.sourceAssetId(), picture.fileName());
                }
            }
        }
        Set<String> suspects = new HashSet<>();
        uses.forEach((assetId, count) -> {
            if (count > properties.getSharedPictureLimit() && !stockIds.contains(assetId)) {
                suspects.add(assetId);
                tally.suspectSharedPictures.add(new SharedPicture(assetId, fileNames.get(assetId), count));
                log.warn("lego_wooden_toys.capture.suspect_shared_picture assetId={} fileName={} entries={}",
                        assetId, fileNames.get(assetId), count);
            }
        });
        return suspects;
    }

    private static boolean differs(Entry entry, HeldEntry held, List<Picture> pictures) {
        if (!Objects.equals(entry.sourceVersion(), held.sourceVersion())
                || !Objects.equals(entry.sourceUpdatedAt(), held.sourceUpdatedAt())) {
            return true;
        }
        // A stored picture the entry no longer lists.
        Set<String> listed = new HashSet<>();
        pictures.forEach(picture -> listed.add(picture.sourceAssetId()));
        return !listed.containsAll(held.legoPictureAssetIds());
    }

    private Optional<Picture> capturePicture(Entry entry, Picture picture, Set<String> stockChecksums, Tally tally) {
        String label = "%s %s (%s)".formatted(entry.slug(), picture.fileName(), picture.sourceAssetId());
        if (picture.byteSize() != null && picture.byteSize() > properties.getMaxPictureBytes()) {
            return problem(tally, label, "larger than the limit: %d bytes".formatted(picture.byteSize()));
        }
        String key = "%s/images/%s/%s-%s".formatted(properties.getObjectPrefix(), entry.uid(),
                picture.sourceAssetId(), picture.fileName().replaceAll("[^A-Za-z0-9._-]", "_"));

        if (s3Service.objectExists(uploadsBucket, key)) {
            Measured inBucket;
            try (InputStream stream = s3Service.getObject(uploadsBucket, key)) {
                inBucket = measure(stream);
            } catch (IOException e) {
                throw new IllegalStateException("could not read " + key + " from the bucket", e);
            }
            tally.picturesReusedFromBucket++;
            return Optional.of(picture.stored(key, inBucket.bytes(), inBucket.sha256()));
        }

        pauser.pause(properties.getPicturePause());
        Path file = null;
        try {
            file = Files.createTempFile("lego-wooden-toys-", ".picture");
            try {
                source.downloadPicture(picture.sourceUrl(), file);
            } catch (SourceException e) {
                if (e.isNotFound()) {
                    return problem(tally, label, e.getMessage());
                }
                throw e;
            }
            Measured downloaded;
            try (InputStream stream = Files.newInputStream(file)) {
                downloaded = measure(stream);
            }
            if (downloaded.bytes() == 0) {
                return problem(tally, label, "the download was empty");
            }
            if (stockChecksums.contains(downloaded.sha256())) {
                tally.stockPicturesRefused++;
                log.warn("lego_wooden_toys.capture.stock_picture_under_new_id entry={} assetId={} fileName={}",
                        entry.slug(), picture.sourceAssetId(), picture.fileName());
                return Optional.empty();
            }
            if (downloaded.bytes() > properties.getMaxPictureBytes()) {
                return problem(tally, label, "larger than the limit: %d bytes".formatted(downloaded.bytes()));
            }
            try (InputStream stream = Files.newInputStream(file)) {
                s3Service.putObject(uploadsBucket, key, stream, downloaded.bytes(),
                        picture.contentType() == null ? BINARY : picture.contentType());
            }
            tally.picturesDownloaded++;
            tally.bytesDownloaded += downloaded.bytes();
            return Optional.of(picture.stored(key, downloaded.bytes(), downloaded.sha256()));
        } catch (IOException e) {
            throw new IllegalStateException("could not handle the downloaded picture " + label, e);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    log.warn("lego_wooden_toys.capture.temp_file_not_deleted file={}", file);
                }
            }
        }
    }

    private static Optional<Picture> problem(Tally tally, String label, String reason) {
        tally.pictureProblems.add(label + ": " + reason);
        log.warn("lego_wooden_toys.capture.picture_not_captured picture=\"{}\" reason=\"{}\"", label, reason);
        return Optional.empty();
    }

    /** Positions from 0 without gaps, in the order kept. */
    private static List<Picture> renumbered(List<Picture> pictures) {
        List<Picture> result = new ArrayList<>();
        for (Picture p : pictures) {
            result.add(new Picture(result.size(), p.sourceAssetId(), p.fileName(), p.contentType(), p.width(),
                    p.height(), p.byteSize(), p.sha256(), p.sourceUrl(), p.objectKey(), p.alreadyHeld()));
        }
        return result;
    }

    private record Measured(long bytes, String sha256) {
    }

    private static Measured measure(InputStream stream) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[64 * 1024];
        long bytes = 0;
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
            bytes += read;
        }
        return new Measured(bytes, HexFormat.of().formatHex(digest.digest()));
    }

    /** A check on the site's answer did not pass. */
    private static class Stop extends RuntimeException {
        Stop(String message) {
            super(message);
        }
    }

    private static class Tally {
        final boolean apply;
        final Instant startedAt;
        Outcome outcome = Outcome.FAILED;
        String message = "";
        int pagesRead;
        int reportedCount;
        int entriesCaptured;
        int entriesInDatabase;
        int entriesNew;
        int entriesChanged;
        int picturesListed;
        int stockPicturesRefused;
        int picturesAlreadyHeld;
        int picturesToCapture;
        int picturesDownloaded;
        int picturesReusedFromBucket;
        long bytesDownloaded;
        final List<String> pictureProblems = new ArrayList<>();
        final List<SharedPicture> suspectSharedPictures = new ArrayList<>();
        String captureFileKey;

        Tally(boolean apply, Instant startedAt) {
            this.apply = apply;
            this.startedAt = startedAt;
        }

        void end(Outcome outcome, String message) {
            this.outcome = outcome;
            this.message = message == null ? "" : message;
        }

        LegoWoodenToysCaptureResult result(Instant finishedAt) {
            return new LegoWoodenToysCaptureResult(outcome, apply, startedAt, finishedAt, message, pagesRead,
                    reportedCount, entriesCaptured, entriesInDatabase, entriesNew, entriesChanged, picturesListed,
                    stockPicturesRefused, picturesAlreadyHeld, picturesToCapture, picturesDownloaded,
                    picturesReusedFromBucket, bytesDownloaded, List.copyOf(pictureProblems),
                    List.copyOf(suspectSharedPictures), captureFileKey);
        }
    }
}
