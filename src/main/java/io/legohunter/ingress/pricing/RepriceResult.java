package io.legohunter.ingress.pricing;

/**
 * One Reprice run.
 *
 * @param listingsSelected      candidate listings (newest snapshot not decided yet, or fixed price not recorded)
 * @param decisionsWritten      pricing_decision rows inserted
 * @param proposed              guard passed, not sent (DRY_RUN)
 * @param blocked               guard said no (decision BLOCKED)
 * @param skipped               fixed-price listings (decision SKIPPED)
 * @param failed                no price could be decided (decision FAILED)
 * @param accepted              decision ACCEPTED and a sync request queued (APPLY)
 * @param appliedLocally        local price written, decision APPLIED (APPLY_LOCAL_ONLY)
 * @param waitingForSync        listings left alone because a change is already in flight; decided again next run
 * @param errors                listings whose pass threw; nothing was saved for them
 * @param handedOff             sync requests processed straight away
 * @param syncConfirmed         of those, confirmed by the marketplace (request SUCCEEDED, decision APPLIED)
 */
public record RepriceResult(
        String outcome,
        RepriceMode mode,
        int listingsSelected,
        int decisionsWritten,
        int proposed,
        int blocked,
        int skipped,
        int failed,
        int accepted,
        int appliedLocally,
        int waitingForSync,
        int errors,
        int handedOff,
        int syncConfirmed,
        long elapsedMillis
) {
    static RepriceResult noWork(RepriceMode mode, long elapsedMillis) {
        return new RepriceResult("NO_WORK", mode, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, elapsedMillis);
    }
}
