package io.legohunter.ingress.marketplace.bricklink;

/**
 * What one lot image sync run did.
 *
 * @param outOfDate         listings whose lot does not show a current primary photo, found this run
 * @param queued            requests created for them
 * @param claimed           requests taken up for sending
 * @param stored            images the gateway stored on a lot
 * @param alreadyCurrent    requests closed without sending, because the lot had caught up
 * @param deferred          requests put back because the gateway or BrickLink could not work
 * @param multiplePrimaries items seen with more than one primary photo
 */
public record BricklinkLotImageSyncResult(
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
        int multiplePrimaries,
        long elapsedMillis
) {
}
