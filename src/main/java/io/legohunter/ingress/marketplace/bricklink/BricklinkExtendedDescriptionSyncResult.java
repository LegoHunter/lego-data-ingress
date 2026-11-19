package io.legohunter.ingress.marketplace.bricklink;

/**
 * What one Extended Description sync run did.
 *
 * @param outOfDate      listings whose lot does not hold the text their captions call for, found this run
 * @param queued         requests created for them
 * @param claimed        requests taken up for sending
 * @param stored         texts the gateway stored on a lot (an empty text clears it)
 * @param alreadyCurrent requests closed without sending, because the lot had caught up
 * @param deferred       requests put back because the gateway or BrickLink could not work
 */
public record BricklinkExtendedDescriptionSyncResult(
        BricklinkMarketplaceSyncMode mode,
        String outcome,
        int outOfDate,
        int queued,
        int claimed,
        int stored,
        int alreadyCurrent,
        int blocked,
        int failed,
        int deferred,
        int dryRunVerified,
        long elapsedMillis
) {
}
