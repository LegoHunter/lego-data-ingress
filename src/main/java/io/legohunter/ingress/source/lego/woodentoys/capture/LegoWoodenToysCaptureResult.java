package io.legohunter.ingress.source.lego.woodentoys.capture;

import java.time.Instant;
import java.util.List;

/**
 * What one capture run did.
 *
 * @param apply                    false for a dry run, which reads lego.com and the database and writes nothing
 * @param message                  why the run stopped or failed; empty otherwise
 * @param reportedCount            entries lego.com said the catalog has
 * @param entriesCaptured          entries read
 * @param entriesInDatabase        captured entries the database held before the run
 * @param entriesNew               entries the database did not hold
 * @param entriesChanged           entries that are new, or whose version, update time or pictures differ
 * @param picturesListed           pictures the entries list, the stock picture included
 * @param stockPicturesRefused     listed pictures that were the stock picture, by id or by checksum
 * @param picturesAlreadyHeld      pictures the database already held, not downloaded
 * @param picturesToCapture        pictures the database did not hold
 * @param picturesDownloaded       pictures downloaded from lego.com and put in the bucket
 * @param picturesReusedFromBucket pictures an earlier run had already put in the bucket, not downloaded again
 * @param bytesDownloaded          total size of the downloaded pictures
 * @param pictureProblems          pictures that were not captured, each with the reason
 * @param suspectSharedPictures    pictures attached to more entries than the limit and not known as the stock
 *                                 picture; not downloaded
 * @param captureFileKey           key of the capture file written, or null when none was written
 */
public record LegoWoodenToysCaptureResult(
        Outcome outcome,
        boolean apply,
        Instant startedAt,
        Instant finishedAt,
        String message,
        int pagesRead,
        int reportedCount,
        int entriesCaptured,
        int entriesInDatabase,
        int entriesNew,
        int entriesChanged,
        int picturesListed,
        int stockPicturesRefused,
        int picturesAlreadyHeld,
        int picturesToCapture,
        int picturesDownloaded,
        int picturesReusedFromBucket,
        long bytesDownloaded,
        List<String> pictureProblems,
        List<SharedPicture> suspectSharedPictures,
        String captureFileKey
) {
    public enum Outcome {
        /** Pictures and a capture file were written to the bucket. */
        COMPLETED,
        /** Nothing differs from what the database holds; nothing was written. */
        UNCHANGED,
        /** A dry run: the counts say what a run would do; nothing was written. */
        DRY_RUN,
        /** The site's answer did not pass a check (unreadable page, too few entries). No capture file was written. */
        STOPPED,
        /** A request or a write failed. No capture file was written. */
        FAILED;

        public boolean failure() {
            return this == STOPPED || this == FAILED;
        }

        public String tag() {
            return name().toLowerCase();
        }
    }

    public record SharedPicture(String sourceAssetId, String fileName, int entries) {
    }
}
