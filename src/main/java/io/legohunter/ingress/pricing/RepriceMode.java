package io.legohunter.ingress.pricing;

/**
 * What the Reprice job does with a decision that passes the guard (HOMELAB-65).
 * {@code lego.bricklink.marketplace-sync.mode} stays the separate switch for writing to BrickLink.
 */
public enum RepriceMode {
    /** Decide, guard and record only: a passing decision stays PROPOSED. */
    DRY_RUN,
    /** Write the local price and mark the decision APPLIED, with no marketplace write. */
    APPLY_LOCAL_ONLY,
    /** Accept the decision, queue its sync request and hand it to marketplace sync straight away. */
    APPLY
}
