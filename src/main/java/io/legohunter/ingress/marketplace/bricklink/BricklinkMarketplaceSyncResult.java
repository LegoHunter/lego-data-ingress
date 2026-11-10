package io.legohunter.ingress.marketplace.bricklink;

public record BricklinkMarketplaceSyncResult(
        String outcome,
        BricklinkMarketplaceSyncMode mode,
        int requestsSelected,
        int requestsClaimed,
        int remoteVerified,
        int remoteUpdated,
        int dryRunVerified,
        int blocked,
        int failed,
        long elapsedMillis
) {
    static BricklinkMarketplaceSyncResult noWork(BricklinkMarketplaceSyncMode mode, long elapsedMillis) {
        return new BricklinkMarketplaceSyncResult("NO_WORK", mode, 0, 0, 0, 0, 0, 0, 0, elapsedMillis);
    }
}
