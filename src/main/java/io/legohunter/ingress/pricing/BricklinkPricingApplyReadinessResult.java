package io.legohunter.ingress.pricing;

public record BricklinkPricingApplyReadinessResult(
        String outcome,
        int decisionsSelected,
        int readyToApply,
        int skippedFixedPrice,
        int skippedMissingCurrentPrice,
        int skippedMissingFinalPrice,
        int skippedCurrencyMismatch,
        int skippedUnsupportedDecisionStatus,
        int skippedBlockedReasonCode,
        int skippedIneligibleReason,
        int skippedBelowMinimumDelta,
        int skippedBelowMinimumDeltaPercent,
        int skippedBelowMinimumConfidence,
        int skippedBelowMinimumComparableCount,
        int skippedAboveMaximumAbsoluteDelta,
        int skippedAboveMaximumPercentDelta,
        int skippedStaleDecision,
        long elapsedMillis
) {
    public static BricklinkPricingApplyReadinessResult noWork(long elapsedMillis) {
        return new BricklinkPricingApplyReadinessResult(
                "NO_WORK",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                elapsedMillis
        );
    }
}
