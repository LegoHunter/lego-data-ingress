package io.legohunter.ingress.marketplace.bricklink;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * One comparison of the BrickLink store with the local listings.
 *
 * @param outcome            {@code IN_SYNC}, {@code DRIFT_FOUND} or {@code FAILED}
 * @param remoteLots         every lot the store returned, in every stockroom
 * @param ownedRemoteLots    lots whose system block names this environment
 * @param localListings      ACTIVE local BrickLink listings with a lot id
 * @param matchedListings    local listings whose lot the store returned
 * @param inFlightListings   matched listings with a PENDING or CLAIMED sync request, so price drift is
 *                           expected for a moment and not reported
 * @param driftCounts        drifts found, by type; every type is present
 */
public record BricklinkInventoryReconciliationReport(
        String outcome,
        String environmentCode,
        ZonedDateTime checkedAt,
        int remoteLots,
        int ownedRemoteLots,
        int localListings,
        int matchedListings,
        int inFlightListings,
        Map<BricklinkInventoryDriftType, Integer> driftCounts,
        List<Drift> drifts,
        String errorMessage,
        long elapsedMillis
) {
    public static final String IN_SYNC = "IN_SYNC";
    public static final String DRIFT_FOUND = "DRIFT_FOUND";
    public static final String FAILED = "FAILED";

    public int totalDrifts() {
        return drifts.size();
    }

    /**
     * One disagreement.
     *
     * @param marketplaceListingId the local listing, or null for an untracked lot
     * @param bricklinkInventoryId the lot, as the listing records it or as the store returned it
     */
    public record Drift(
            BricklinkInventoryDriftType type,
            Integer marketplaceListingId,
            Long bricklinkInventoryId,
            String expected,
            String actual
    ) {
    }
}
