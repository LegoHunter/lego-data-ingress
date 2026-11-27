package io.legohunter.ingress.source.lego.woodentoys.capture;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The capture file: one capture of LEGO's wooden toys catalog, as the capture job writes it to the uploads bucket
 * at {@code lego/wooden-toys/catalog/<capture time>.json} and as the load reads it.
 *
 * <p>It always holds every entry lego.com listed, changed or not, so the load can tell which stored entries the
 * site no longer lists. Text fields are trimmed, and an empty one is left out.
 *
 * @param schemaVersion   {@link #SCHEMA_VERSION}; raised when the format changes in a way the load must know
 * @param source          {@link #SOURCE}
 * @param capturedAt      when the capture started
 * @param reportedCount   the number of entries lego.com said the catalog has
 * @param entries         every entry, in the site's order
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WoodenToysCaptureFile(
        int schemaVersion,
        String source,
        Instant capturedAt,
        int reportedCount,
        List<Entry> entries
) {
    public static final int SCHEMA_VERSION = 1;
    public static final String SOURCE = "LEGO_COM";

    /**
     * One catalog entry.
     *
     * @param uid                    LEGO's id of the entry; never changes
     * @param slug                   last part of the entry's address, the entry's key in the catalog
     * @param url                    the entry's page
     * @param productNumber          Product Number as printed, for example {@code 112/b}; not unique
     * @param productNumberSortable  LEGO's sortable form of the Product Number
     * @param alternateNumberText    Alternate Number
     * @param launchYearText         Launch Year as text, for example {@code 1930'erne (primo)}
     * @param exitYearText           Exit Year as text
     * @param notesOriginal          LEGO's note in Danish
     * @param notesEnglish           LEGO's note in English
     * @param sourceVersion          LEGO's version number of the entry
     * @param sourceUpdatedAt        when LEGO last changed the entry
     * @param pictures               the entry's pictures in LEGO's order, without the stock picture
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(
            String uid,
            String slug,
            String url,
            String productNumber,
            String productNumberSortable,
            String alternateNumberText,
            String originalTitle,
            String englishTitle,
            String launchYearText,
            String exitYearText,
            String averageSizeText,
            String seriesText,
            String notesOriginal,
            String notesEnglish,
            Integer sourceVersion,
            Instant sourceUpdatedAt,
            List<Picture> pictures
    ) {
        public Entry withPictures(List<Picture> newPictures) {
            return new Entry(uid, slug, url, productNumber, productNumberSortable, alternateNumberText, originalTitle,
                    englishTitle, launchYearText, exitYearText, averageSizeText, seriesText, notesOriginal,
                    notesEnglish, sourceVersion, sourceUpdatedAt, newPictures);
        }
    }

    /**
     * One picture of an entry.
     *
     * <p>A picture the database already held at capture time has {@code alreadyHeld} true and no {@code objectKey}
     * or {@code sha256}: it is listed so the load knows the entry still has it, and its bytes are not sent again.
     * Otherwise {@code objectKey} names the object in the same bucket that holds the bytes, and {@code sha256} and
     * {@code byteSize} describe those bytes.
     *
     * @param sortOrder      position within the entry, from 0
     * @param sourceAssetId  LEGO's id of the picture
     * @param fileName       LEGO's file name
     * @param width          pixels, as LEGO states it
     * @param height         pixels, as LEGO states it
     * @param byteSize       size of the bytes in the bucket; for a picture already held, the size LEGO states
     * @param sha256         lower case hex SHA-256 of the bytes in the bucket
     * @param sourceUrl      where the picture was downloaded from
     * @param objectKey      key of the picture's object in the uploads bucket
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Picture(
            int sortOrder,
            String sourceAssetId,
            String fileName,
            String contentType,
            Integer width,
            Integer height,
            Long byteSize,
            String sha256,
            String sourceUrl,
            String objectKey,
            boolean alreadyHeld
    ) {
        public Picture held() {
            return new Picture(sortOrder, sourceAssetId, fileName, contentType, width, height, byteSize, null,
                    sourceUrl, null, true);
        }

        public Picture stored(String key, long bytes, String checksum) {
            return new Picture(sortOrder, sourceAssetId, fileName, contentType, width, height, bytes, checksum,
                    sourceUrl, key, false);
        }
    }
}
