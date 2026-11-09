package io.legohunter.ingress.marketplace.bricklink;

/** Ways a lot tagged for a lower environment can be exposed or misplaced. */
public enum BricklinkLotAuditViolationType {
    /** Not in any stockroom, so buyers can see it. */
    LOWER_ENV_LOT_PUBLIC,
    /** In stockroom A, which only PROD may use. */
    LOWER_ENV_LOT_IN_PROD_STOCKROOM,
    /** In a stockroom other than its own environment's (SANDBOX must be C, DEV must be B). */
    LOWER_ENV_LOT_IN_WRONG_STOCKROOM;

    public String tag() {
        return name().toLowerCase();
    }
}
