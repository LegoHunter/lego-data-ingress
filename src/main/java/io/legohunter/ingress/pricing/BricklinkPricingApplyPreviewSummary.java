package io.legohunter.ingress.pricing;

import java.util.Map;

public record BricklinkPricingApplyPreviewSummary(
        int returnedCount,
        int readyToApplyCount,
        int blockedCount,
        Map<String, Integer> readinessStatusCounts,
        Map<String, Integer> blockReasonCounts
) {
}
