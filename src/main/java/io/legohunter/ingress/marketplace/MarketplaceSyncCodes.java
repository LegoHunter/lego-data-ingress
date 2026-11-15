package io.legohunter.ingress.marketplace;

/**
 * Codes written to {@code marketplace_listing_sync_request} by the pricing side (Reprice) and read by every
 * marketplace's sync worker.
 */
public final class MarketplaceSyncCodes {
    /** Create the marketplace listing (lot) for a priced draft. */
    public static final String TYPE_LISTING_CREATE = "LISTING_CREATE";
    /** Change the price of an existing listing. */
    public static final String TYPE_PRICE_UPDATE = "PRICE_UPDATE";

    public static final String STATUS_PENDING = "PENDING";

    /** A pricing decision changed an existing listing's price. */
    public static final String REASON_PRICING_DECISION_APPLIED = "PRICING_DECISION_APPLIED";
    /** A draft got its first price and is being listed. */
    public static final String REASON_INITIAL_PRICE_APPLIED = "INITIAL_PRICE_APPLIED";

    /** The listing is hidden in a stockroom (always, outside PROD). */
    public static final String REMOTE_SCOPE_STOCKROOM = "STOCKROOM";
    /** The listing is visible to buyers. */
    public static final String REMOTE_SCOPE_PUBLIC = "PUBLIC";

    private MarketplaceSyncCodes() {
    }
}
