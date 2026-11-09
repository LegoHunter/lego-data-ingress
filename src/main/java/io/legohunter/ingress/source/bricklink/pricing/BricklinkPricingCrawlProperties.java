package io.legohunter.ingress.source.bricklink.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.pricing.crawl")
public class BricklinkPricingCrawlProperties {
    private boolean enabled = false;
    private Integer bricklinkExternalServiceId = 2;
    private Set<String> priceableListingStatusCodes = Set.of("ACTIVE", "DRAFT");
    private String activeListingStatusCode = "ACTIVE";
    private String catalogItemType = "S";
    private int batchSize = 25;
    private int workerBatchSize = 1;
    private int resultsPerPage = 500;
    private int maxAttempts = 3;
    private Duration crawlCadence = Duration.ofDays(7);
    private Duration scheduleSpreadWindow = Duration.ofHours(72);
    private Duration retryBackoff = Duration.ofHours(6);
    private Duration claimStaleAfter = Duration.ofHours(2);
    private Duration scheduleJitter = Duration.ZERO;
    /**
     * Who holds a crawl claim, for operational review in crawl_target.claimed_by. Defaults to the pod's
     * host name.
     */
    private String claimedBy;
    private Blackout blackout = new Blackout();
    private Scheduled scheduled = new Scheduled();

    public String effectiveActiveListingStatusCode() {
        if (activeListingStatusCode == null || activeListingStatusCode.isBlank()) {
            return "ACTIVE";
        }
        return activeListingStatusCode.trim().toUpperCase();
    }

    public Set<String> effectivePriceableListingStatusCodes() {
        if (priceableListingStatusCodes == null || priceableListingStatusCodes.isEmpty()) {
            return Set.of(effectiveActiveListingStatusCode());
        }
        Set<String> normalizedStatusCodes = priceableListingStatusCodes.stream()
                .filter(statusCode -> statusCode != null && !statusCode.isBlank())
                .map(statusCode -> statusCode.trim().toUpperCase())
                .collect(Collectors.toUnmodifiableSet());
        return normalizedStatusCodes.isEmpty() ? Set.of(effectiveActiveListingStatusCode()) : normalizedStatusCodes;
    }

    public String effectiveCatalogItemType() {
        if (catalogItemType == null || catalogItemType.isBlank()) {
            return "S";
        }
        return catalogItemType.trim().toUpperCase();
    }

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public int effectiveWorkerBatchSize() {
        return Math.max(1, workerBatchSize);
    }

    public int effectiveResultsPerPage() {
        return Math.max(1, resultsPerPage);
    }

    public int effectiveMaxAttempts() {
        return Math.max(1, maxAttempts);
    }

    public Duration effectiveCrawlCadence() {
        return positiveOrDefault(crawlCadence, Duration.ofDays(7));
    }

    public Duration effectiveScheduleSpreadWindow() {
        return nonNegativeOrDefault(scheduleSpreadWindow, Duration.ofHours(72));
    }

    public Duration effectiveRetryBackoff() {
        return positiveOrDefault(retryBackoff, Duration.ofHours(6));
    }

    public Duration effectiveClaimStaleAfter() {
        return positiveOrDefault(claimStaleAfter, Duration.ofHours(2));
    }

    public Duration effectiveScheduleJitter() {
        return nonNegativeOrDefault(scheduleJitter, Duration.ZERO);
    }

    public String effectiveClaimedBy() {
        if (claimedBy != null && !claimedBy.isBlank()) {
            return claimedBy.trim();
        }
        String host = System.getenv("HOSTNAME");
        return host == null || host.isBlank() ? "lego-data-ingress" : host.trim();
    }

    private Duration positiveOrDefault(Duration value, Duration defaultValue) {
        if (value == null || value.isZero() || value.isNegative()) {
            return defaultValue;
        }
        return value;
    }

    private Duration nonNegativeOrDefault(Duration value, Duration defaultValue) {
        if (value == null || value.isNegative()) {
            return defaultValue;
        }
        return value;
    }

    @Getter
    @Setter
    public static class Blackout {
        private boolean enabled = false;
        private String zoneId = "America/New_York";
        private LocalTime start = LocalTime.of(21, 30);
        private LocalTime end = LocalTime.of(8, 30);
        private boolean weekdaysOnly = true;
    }

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        private long fixedDelayMs = 300_000L;
        private long initialDelayMs = 30_000L;
        private String lockAtMostFor = "30m";
        private String lockAtLeastFor = "0s";
    }
}
