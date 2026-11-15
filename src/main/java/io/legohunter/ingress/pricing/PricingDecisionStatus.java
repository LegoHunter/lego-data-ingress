package io.legohunter.ingress.pricing;

/**
 * The values of {@code pricing_decision.decision_status_code}, in the order a decision can move through them
 * (HOMELAB-23 design 4.3). Reprice writes the first five; marketplace sync moves an ACCEPTED decision on.
 */
public final class PricingDecisionStatus {
    /** The guard passed but nothing was sent: DRY_RUN, or a change was already in flight. */
    public static final String PROPOSED = "PROPOSED";
    /** The guard said no; {@code guard_reason_code} says why. */
    public static final String BLOCKED = "BLOCKED";
    /** Not repriced: a fixed-price listing. */
    public static final String SKIPPED = "SKIPPED";
    /** No price could be decided: no snapshot, no comparables or missing item data. */
    public static final String FAILED = "FAILED";
    /** A sync request was queued for the marketplace. */
    public static final String ACCEPTED = "ACCEPTED";
    /** The marketplace confirmed the price (or, in APPLY_LOCAL_ONLY, it was applied locally). */
    public static final String APPLIED = "APPLIED";
    /** ACCEPTED, but its sync request ended BLOCKED or FAILED, so the marketplace never got the price. */
    public static final String SYNC_FAILED = "SYNC_FAILED";

    private PricingDecisionStatus() {
    }
}
