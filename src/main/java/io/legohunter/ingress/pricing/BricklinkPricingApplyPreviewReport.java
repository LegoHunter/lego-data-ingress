package io.legohunter.ingress.pricing;

import io.legohunter.data.dto.PricingApplyReadinessReview;

import java.time.ZonedDateTime;
import java.util.Set;

public record BricklinkPricingApplyPreviewReport(
        ZonedDateTime generatedAt,
        boolean dryRun,
        String readinessStatusCode,
        String blockReasonCode,
        BricklinkPricingApplyPreviewSummary summary,
        Set<PricingApplyReadinessReview> readinessReviews
) {
}
