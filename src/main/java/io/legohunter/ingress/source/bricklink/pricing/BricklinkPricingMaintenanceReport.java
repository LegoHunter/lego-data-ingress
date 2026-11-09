package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dto.CrawlAttempt;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Dry-run maintenance view of the shared BrickLink crawl (HOMELAB-32): the crawl_target queue by state and the
 * latest failed attempts from crawl_attempt.
 */
public record BricklinkPricingMaintenanceReport(
        ZonedDateTime generatedAt,
        boolean dryRun,
        CrawlTargetSummary crawlTargets,
        List<CrawlAttempt> recentFailures
) {
    /** Crawl targets by state. {@code failing} counts non-disabled targets whose latest attempt failed. */
    public record CrawlTargetSummary(long idle, long claimed, long disabled, long due, long failing) {
    }
}
