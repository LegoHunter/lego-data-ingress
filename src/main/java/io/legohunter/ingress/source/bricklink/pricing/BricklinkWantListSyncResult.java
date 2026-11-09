package io.legohunter.ingress.source.bricklink.pricing;

/**
 * What one want list sync did (HOMELAB-32).
 *
 * @param itemsConfirmed     want list rows written or refreshed for this environment
 * @param canonicalItemsAdded canonical items this environment was the first to want
 * @param itemsSkipped       wanted items left out because their completeness has no canonical code
 * @param itemsPruned        this environment's rows removed because it no longer prices the item
 */
public record BricklinkWantListSyncResult(
        String environmentCode,
        int itemsConfirmed,
        int canonicalItemsAdded,
        int itemsSkipped,
        int itemsPruned,
        long elapsedMillis
) {
}
