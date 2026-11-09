package io.legohunter.ingress.source.bricklink.pricing;

/**
 * What one run of the shared BrickLink crawler did (HOMELAB-32).
 *
 * @param outcome            NO_WORK, BLACKOUT (scheduled only, no fetch during the blackout window), SCHEDULED,
 *                           SUCCESS or PARTIAL_SUCCESS (at least one fetch failed)
 * @param targetsCreated     new crawl targets for item + condition pairs some environment started wanting
 * @param targetsReenabled   DISABLED targets wanted again
 * @param targetsDisabled    IDLE targets no environment wants any more
 * @param targetsClaimed     targets this run claimed and fetched
 * @param snapshotsWritten   successful fetches, one market snapshot each
 * @param emptySnapshots     successful fetches that found no lots for sale
 * @param listingsWritten    competitor lots stored
 * @param idItemsResolved    BrickLink internal item ids looked up and stored for reuse
 * @param failedFetches      fetches that failed (HTTP, parse, or item lookup); each is in crawl_attempt
 */
public record BricklinkPricingCrawlResult(
        String outcome,
        int targetsCreated,
        int targetsReenabled,
        int targetsDisabled,
        int targetsClaimed,
        int snapshotsWritten,
        int emptySnapshots,
        int listingsWritten,
        int idItemsResolved,
        int failedFetches,
        long elapsedMillis
) {
    public static BricklinkPricingCrawlResult noWork(long elapsedMillis) {
        return new BricklinkPricingCrawlResult("NO_WORK", 0, 0, 0, 0, 0, 0, 0, 0, 0, elapsedMillis);
    }
}
