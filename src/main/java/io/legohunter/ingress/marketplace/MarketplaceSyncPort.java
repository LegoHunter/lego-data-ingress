package io.legohunter.ingress.marketplace;

/**
 * One marketplace's sync worker, as the Reprice job sees it (HOMELAB-23 design 4.6, built in HOMELAB-65).
 * Reprice queues a request in {@code marketplace_listing_sync_request} and hands its id to the port for
 * the listing's marketplace, so the price goes out in the same run. The marketplace's own sync job still
 * processes everything the hand-off does not (retries, requests from lego-data-service).
 *
 * <p>BrickLink is the only adapter today; eBay comes with HOMELAB-33.
 */
public interface MarketplaceSyncPort {

    /** Whether this adapter syncs listings of {@code listing_external_service_id}. */
    boolean handles(Integer listingExternalServiceId);

    /**
     * Claims the PENDING request and processes it now, through the same code path as the sync job. A
     * failure is recorded on the request (it stays PENDING for a retry, or ends BLOCKED or FAILED), never
     * thrown.
     */
    HandOff processNow(Long marketplaceListingSyncRequestId);

    /**
     * @param sent          whether the request was claimed and processed
     * @param requestStatus the request's status afterwards (SUCCEEDED, PENDING for a retry, BLOCKED or
     *                      FAILED); when not sent, why: {@link #SYNC_NOT_APPLY}, {@link #NOT_PENDING} or
     *                      {@link #CLAIMED_ELSEWHERE}
     */
    record HandOff(boolean sent, String requestStatus) {
        /** Marketplace sync is in DRY_RUN: the request waits for the sync job. */
        public static final String SYNC_NOT_APPLY = "SYNC_NOT_APPLY";
        /** The request is missing, not PENDING, or of a type this adapter does not process. */
        public static final String NOT_PENDING = "NOT_PENDING";
        /** Another worker claimed it first. */
        public static final String CLAIMED_ELSEWHERE = "CLAIMED_ELSEWHERE";

        public static HandOff notSent(String why) {
            return new HandOff(false, why);
        }
    }
}
