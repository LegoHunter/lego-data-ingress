package io.legohunter.ingress.pricing;

public record BricklinkPricingDecisionResult(
        String outcome,
        int listingsSelected,
        int decisionsWritten,
        int proposedDecisions,
        int skippedDecisions,
        int failedDecisions,
        long elapsedMillis
) {
    public static BricklinkPricingDecisionResult noWork(long elapsedMillis) {
        return new BricklinkPricingDecisionResult("NO_WORK", 0, 0, 0, 0, 0, elapsedMillis);
    }
}
