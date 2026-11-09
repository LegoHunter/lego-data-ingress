package io.legohunter.ingress.pricing;

public record BricklinkPricingApplyResult(
        String outcome,
        boolean applyEnabled,
        BricklinkPricingApplyMode mode,
        int readinessRowsSelected,
        int localPricesUpdated,
        int syncRequestsEnqueued,
        int dryRunSelections,
        int skippedRows,
        int failedRows,
        long elapsedMillis
) {
    static BricklinkPricingApplyResult noWork(boolean applyEnabled, BricklinkPricingApplyMode mode, long elapsedMillis) {
        return new BricklinkPricingApplyResult("NO_WORK", applyEnabled, mode, 0, 0, 0, 0, 0, 0, elapsedMillis);
    }
}
