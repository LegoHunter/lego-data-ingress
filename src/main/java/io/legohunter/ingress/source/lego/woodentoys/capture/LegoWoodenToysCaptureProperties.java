package io.legohunter.ingress.source.lego.woodentoys.capture;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings for the capture of LEGO's wooden toys catalog from lego.com ({@link LegoWoodenToysCaptureService}).
 *
 * <p>{@code enabled} creates the capture and its on-demand endpoint; {@code scheduled.enabled} adds the monthly
 * run. The capture reads lego.com and writes to this environment's uploads bucket; it does not write to the
 * database. The load of what it writes is a separate step, started by the capture file arriving in the bucket.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = LegoWoodenToysCaptureProperties.PREFIX)
public class LegoWoodenToysCaptureProperties {
    public static final String PREFIX = "lego.catalog-capture.lego-wooden-toys";

    private boolean enabled = false;
    private Scheduled scheduled = new Scheduled();

    /** The site's address, without a trailing slash. */
    private String baseUrl = "https://www.lego.com";
    /** Path of the first overview page. Page N is this path plus {@code /page-N}. */
    private String overviewPath = "/en-us/history/wooden-toys/overview";
    /** An entry's page is this prefix plus the entry's own path, for example {@code /wooden-toys/330-ferry-1957}. */
    private String entryPathPrefix = "/en-us/history";
    /** The part of an entry's path that is not its slug. An entry whose path does not start with it stops the run. */
    private String entrySlugPrefix = "/wooden-toys/";
    /** A picture is downloaded only from an address that starts with this. */
    private String pictureUrlPrefix = "https://www.lego.com/cdn/";
    private String userAgent = "lego-data-ingress";

    /** Wait between two overview page requests. */
    private Duration pagePause = Duration.ofSeconds(2);
    /** Wait between two picture downloads. */
    private Duration picturePause = Duration.ofSeconds(1);
    private Duration connectTimeout = Duration.ofSeconds(20);
    private Duration pageTimeout = Duration.ofSeconds(60);
    private Duration pictureTimeout = Duration.ofMinutes(10);
    /** Tries per request, the first included. Only a server error or a connection failure is tried again. */
    private int maxAttempts = 3;
    private Duration retryPause = Duration.ofSeconds(15);
    /** A catalog that reports more pages than this stops the run. */
    private int maxPages = 100;

    /** Ids of LEGO's stock "image on its way" picture. A picture with one of these ids is never downloaded. */
    private List<String> stockPictureAssetIds = new ArrayList<>(List.of("blt360cf3fd32e46eb8"));
    /** SHA-256 of the stock picture's bytes, lower case. A downloaded picture with one of these is not kept. */
    private List<String> stockPictureSha256 = new ArrayList<>(
            List.of("8675dfe7f799366bea86e039f5d1032050762777b823a8ea125cc6a317797a37"));
    /**
     * A picture attached to more entries than this is taken for a new stock picture: it is reported and not
     * downloaded. The most entries a real picture is attached to was 7 on 2026-11-26; the stock picture was on 226.
     */
    private int sharedPictureLimit = 20;
    /** The run stops when it reads fewer entries than this share of the captured entries the database holds. */
    private double minimumEntryRatio = 0.9d;
    /** A picture LEGO lists as larger than this is reported and not downloaded. The database packet limit is 64 MB. */
    private long maxPictureBytes = 60_000_000L;

    /** Object key prefix in the uploads bucket, without a trailing slash. */
    private String objectPrefix = "lego/wooden-toys";

    public String pageUrl(int page) {
        return baseUrl + overviewPath + (page <= 1 ? "" : "/page-" + page);
    }

    public String entryUrl(String entryPath) {
        return baseUrl + entryPathPrefix + entryPath;
    }

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        /** 04:15 on the 2nd of each month. */
        private String cron = "0 15 4 2 * *";
        private String lockAtMostFor = "15m";
        private String lockAtLeastFor = "10m";
    }
}
