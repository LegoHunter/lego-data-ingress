package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dto.PricingDataQualityGap;
import io.legohunter.data.dto.PricingDataQualitySummary;

import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Listings in the crawl's priceable statuses whose inventory or catalog data is missing something
 * the Pricing Plane needs. {@code summary} counts every such listing; {@code gaps} lists at most
 * {@code limit} of them, lowest listing id first.
 */
public record BricklinkPricingDataQualityReport(
        ZonedDateTime generatedAt,
        Set<String> listingStatusCodes,
        int limit,
        PricingDataQualitySummary summary,
        Set<PricingDataQualityGap> gaps
) {
}
