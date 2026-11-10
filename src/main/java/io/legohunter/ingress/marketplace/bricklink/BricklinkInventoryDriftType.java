package io.legohunter.ingress.marketplace.bricklink;

/** The ways a BrickLink lot can disagree with the local listing that owns it. */
public enum BricklinkInventoryDriftType {
    /** An ACTIVE local listing points at a lot the store no longer has: sold, or deleted by hand. */
    MISSING_REMOTE_LOT,
    /** The lot's price differs from the listing's local price. */
    PRICE_DRIFT,
    /** The lot's quantity differs from the quantity this listing put up. */
    QUANTITY_DRIFT,
    /** The lot's stockroom flag or stockroom differs from what this environment requires or recorded. */
    VISIBILITY_DRIFT,
    /** The lot's system remarks block is missing or names another environment or listing. */
    REMARKS_DRIFT,
    /** The store has a lot tagged for this environment that no local listing owns. */
    UNTRACKED_REMOTE_LOT;

    /** Lower-case tag value for metrics. */
    public String tag() {
        return name().toLowerCase();
    }
}
