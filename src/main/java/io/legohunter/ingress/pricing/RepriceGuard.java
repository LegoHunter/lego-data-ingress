package io.legohunter.ingress.pricing;

import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.PricingDecision;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Whether a decided price may be sent: a pure function of the listing, the decision and the guard
 * settings, with no reads or writes (HOMELAB-65). The rules are the apply-readiness rules
 * ({@link BricklinkPricingApplyReadinessService}) in the same order, minus the stale-decision check,
 * which Reprice does not need because it decides and guards in one pass on the same data.
 *
 * <p>Only a PROPOSED decision is guarded; SKIPPED (fixed price) and FAILED decisions have no price to send.
 */
public final class RepriceGuard {
    public static final String PASSED = "PASSED";
    public static final String BLOCKED = "BLOCKED";

    public static final String FIXED_PRICE = "FIXED_PRICE";
    public static final String MISSING_FINAL_PRICE = "MISSING_FINAL_PRICE";
    public static final String MISSING_CURRENT_PRICE = "MISSING_CURRENT_PRICE";
    public static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    public static final String BLOCKED_REASON_CODE = "BLOCKED_REASON_CODE";
    public static final String INELIGIBLE_REASON = "INELIGIBLE_REASON";
    public static final String PRICE_UNCHANGED = "PRICE_UNCHANGED";
    public static final String BELOW_MINIMUM_DELTA = "BELOW_MINIMUM_DELTA";
    public static final String BELOW_MINIMUM_DELTA_PERCENT = "BELOW_MINIMUM_DELTA_PERCENT";
    public static final String BELOW_MINIMUM_CONFIDENCE = "BELOW_MINIMUM_CONFIDENCE";
    public static final String BELOW_MINIMUM_COMPARABLE_COUNT = "BELOW_MINIMUM_COMPARABLE_COUNT";
    public static final String ABOVE_MAXIMUM_ABSOLUTE_DELTA = "ABOVE_MAXIMUM_ABSOLUTE_DELTA";
    public static final String ABOVE_MAXIMUM_PERCENT_DELTA = "ABOVE_MAXIMUM_PERCENT_DELTA";
    /** An ACTIVE (or other non-draft) listing with no BrickLink lot: there is nothing to reprice. */
    public static final String NO_REMOTE_LISTING = "NO_REMOTE_LISTING";
    /** An unpriced draft that already has a BrickLink lot: its first price must not create a second lot. */
    public static final String DRAFT_HAS_REMOTE_LISTING = "DRAFT_HAS_REMOTE_LISTING";

    private static final String LISTING_STATUS_DRAFT = "DRAFT";

    private RepriceGuard() {
    }

    /**
     * @param listing     the listing as it is now
     * @param hasRemoteLot whether the listing has a BrickLink lot (a {@code bricklink_marketplace_listing}
     *                    row with an inventory id)
     * @param decision    the PROPOSED decision just made for it
     * @param config      the guard settings ({@code lego.bricklink.pricing.apply-readiness} until HOMELAB-66)
     */
    public static Result evaluate(
            MarketplaceListing listing,
            boolean hasRemoteLot,
            PricingDecision decision,
            BricklinkPricingApplyReadinessProperties config
    ) {
        if (Boolean.TRUE.equals(listing.getFixedPrice())) {
            return Result.blocked(FIXED_PRICE);
        }
        BigDecimal proposed = decision.getFinalPrice();
        if (proposed == null || proposed.signum() <= 0) {
            return Result.blocked(MISSING_FINAL_PRICE);
        }
        boolean initialPrice = isInitialPrice(listing);
        BigDecimal current = listing.getUnitPrice();
        if (!initialPrice && current == null) {
            return Result.blocked(MISSING_CURRENT_PRICE);
        }
        if (!currency(listing.getCurrencyCode()).equals(currency(decision.getCurrencyCode()))) {
            return Result.blocked(CURRENCY_MISMATCH);
        }
        String reasonCode = upper(decision.getReasonCode());
        if (config.effectiveBlockedReasonCodes().contains(reasonCode)) {
            return Result.blocked(BLOCKED_REASON_CODE);
        }
        if (!config.effectiveApplyEligibleReasonCodes().contains(reasonCode)) {
            return Result.blocked(INELIGIBLE_REASON);
        }

        if (initialPrice) {
            if (hasRemoteLot) {
                return Result.blocked(DRAFT_HAS_REMOTE_LISTING);
            }
            if (confidence(decision).compareTo(config.effectiveMinimumConfidence()) < 0) {
                return Result.blocked(BELOW_MINIMUM_CONFIDENCE);
            }
            if (comparableCount(decision) < config.effectiveMinimumComparableCount()) {
                return Result.blocked(BELOW_MINIMUM_COMPARABLE_COUNT);
            }
            return Result.passed(true);
        }

        BigDecimal delta = money(proposed).subtract(money(current)).abs();
        if (delta.signum() == 0) {
            return Result.blocked(PRICE_UNCHANGED);
        }
        if (delta.compareTo(config.effectiveMinimumPriceDelta()) < 0) {
            return Result.blocked(BELOW_MINIMUM_DELTA);
        }
        if (config.isMinimumDeltaPercentEnabled() && current.signum() > 0) {
            BigDecimal percent = config.effectiveMinimumDeltaPercent();
            BigDecimal minimum = percent.signum() <= 0
                    ? BigDecimal.ZERO
                    : current.abs().multiply(percent).setScale(2, RoundingMode.CEILING);
            if (delta.compareTo(minimum) < 0) {
                return Result.blocked(BELOW_MINIMUM_DELTA_PERCENT);
            }
        }
        if (confidence(decision).compareTo(config.effectiveMinimumConfidence()) < 0) {
            return Result.blocked(BELOW_MINIMUM_CONFIDENCE);
        }
        if (comparableCount(decision) < config.effectiveMinimumComparableCount()) {
            return Result.blocked(BELOW_MINIMUM_COMPARABLE_COUNT);
        }
        BigDecimal maximumAbsolute = config.effectiveMaximumAbsoluteDelta();
        if (maximumAbsolute != null && delta.compareTo(maximumAbsolute) > 0) {
            return Result.blocked(ABOVE_MAXIMUM_ABSOLUTE_DELTA);
        }
        BigDecimal maximumPercent = config.effectiveMaximumPercentDelta();
        if (maximumPercent != null) {
            if (current.signum() <= 0) {
                return Result.blocked(MISSING_CURRENT_PRICE);
            }
            if (delta.divide(current.abs(), 8, RoundingMode.HALF_UP).compareTo(maximumPercent) > 0) {
                return Result.blocked(ABOVE_MAXIMUM_PERCENT_DELTA);
            }
        }
        if (!hasRemoteLot && !LISTING_STATUS_DRAFT.equals(upper(listing.getListingStatusCode()))) {
            return Result.blocked(NO_REMOTE_LISTING);
        }
        return Result.passed(false);
    }

    /** An unpriced draft that has never been listed: its first price, guarded by confidence and comparables only. */
    static boolean isInitialPrice(MarketplaceListing listing) {
        return LISTING_STATUS_DRAFT.equals(upper(listing.getListingStatusCode()))
                && listing.getUnitPrice() == null
                && (listing.getExternalListingId() == null || listing.getExternalListingId().isBlank());
    }

    private static BigDecimal confidence(PricingDecision decision) {
        return decision.getConfidence() == null ? BigDecimal.ZERO : decision.getConfidence();
    }

    private static int comparableCount(PricingDecision decision) {
        return decision.getComparableCount() == null ? 0 : decision.getComparableCount();
    }

    private static String currency(String value) {
        return value == null || value.isBlank() ? "USD" : value.trim().toUpperCase();
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? "" : value.trim().toUpperCase();
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * @param status       {@link #PASSED} or {@link #BLOCKED}
     * @param reasonCode   why it was blocked; null when it passed
     * @param initialPrice whether this is a draft's first price
     */
    public record Result(String status, String reasonCode, boolean initialPrice) {
        static Result passed(boolean initialPrice) {
            return new Result(PASSED, null, initialPrice);
        }

        static Result blocked(String reasonCode) {
            return new Result(BLOCKED, reasonCode, false);
        }

        public boolean isPassed() {
            return PASSED.equals(status);
        }
    }
}
