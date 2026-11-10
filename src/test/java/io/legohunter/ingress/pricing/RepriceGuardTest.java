package io.legohunter.ingress.pricing;

import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.PricingDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RepriceGuardTest {
    private BricklinkPricingApplyReadinessProperties config;

    @BeforeEach
    void setUp() {
        // SANDBOX's guard settings.
        config = new BricklinkPricingApplyReadinessProperties();
        config.setMinimumPriceDelta(new BigDecimal("0.01"));
        config.getMinimumDelta().setEnabled(true);
        config.getMinimumDelta().setPercent(new BigDecimal("0.02"));
        config.setMinimumConfidence(new BigDecimal("0.75"));
        config.setMinimumComparableCount(3);
        config.setMaximumAbsoluteDelta(new BigDecimal("50.00"));
        config.setMaximumPercentDelta(new BigDecimal("0.50"));
        config.setBlockedReasonCodes(Set.of("NO_EXACT_COMPARABLES"));
    }

    @Test
    void passesAnOrdinaryPriceMoveOnAListedLot() {
        RepriceGuard.Result result = RepriceGuard.evaluate(active("200.00"), true, decision("210.00"), config);

        assertThat(result.isPassed()).isTrue();
        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(result.reasonCode()).isNull();
        assertThat(result.initialPrice()).isFalse();
    }

    @Test
    void blocksAFixedPriceListing() {
        MarketplaceListing listing = active("499.00");
        listing.setFixedPrice(true);

        assertBlocked(RepriceGuard.evaluate(listing, true, decision("450.00"), config), "FIXED_PRICE");
    }

    @Test
    void blocksAMissingOrZeroFinalPrice() {
        assertBlocked(RepriceGuard.evaluate(active("200.00"), true, decision(null), config), "MISSING_FINAL_PRICE");
        assertBlocked(RepriceGuard.evaluate(active("200.00"), true, decision("0.00"), config), "MISSING_FINAL_PRICE");
    }

    @Test
    void blocksAnActiveListingWithoutACurrentPrice() {
        assertBlocked(RepriceGuard.evaluate(active(null), true, decision("210.00"), config), "MISSING_CURRENT_PRICE");
    }

    @Test
    void blocksACurrencyMismatch() {
        PricingDecision decision = decision("210.00");
        decision.setCurrencyCode("EUR");

        assertBlocked(RepriceGuard.evaluate(active("200.00"), true, decision, config), "CURRENCY_MISMATCH");
    }

    @Test
    void blocksConfiguredAndIneligibleReasonCodes() {
        PricingDecision blockedReason = decision("210.00");
        blockedReason.setReasonCode("NO_EXACT_COMPARABLES");
        PricingDecision unknownReason = decision("210.00");
        unknownReason.setReasonCode("SOMETHING_NEW");

        assertBlocked(RepriceGuard.evaluate(active("200.00"), true, blockedReason, config), "BLOCKED_REASON_CODE");
        assertBlocked(RepriceGuard.evaluate(active("200.00"), true, unknownReason, config), "INELIGIBLE_REASON");
    }

    @Test
    void blocksAnUnchangedPriceEvenWithNoMinimumMove() {
        config.setMinimumPriceDelta(BigDecimal.ZERO);
        config.getMinimumDelta().setEnabled(false);

        assertBlocked(RepriceGuard.evaluate(active("200.00"), true, decision("200.00"), config), "PRICE_UNCHANGED");
    }

    @Test
    void blocksMovesBelowTheMinimumAmountAndPercent() {
        config.setMinimumPriceDelta(new BigDecimal("1.00"));
        assertBlocked(RepriceGuard.evaluate(active("200.00"), true, decision("200.50"), config), "BELOW_MINIMUM_DELTA");

        config.setMinimumPriceDelta(new BigDecimal("0.01"));
        // 2% of 200.00 is 4.00: a 3.00 move is too small.
        assertBlocked(RepriceGuard.evaluate(active("200.00"), true, decision("203.00"), config), "BELOW_MINIMUM_DELTA_PERCENT");
        assertThat(RepriceGuard.evaluate(active("200.00"), true, decision("204.00"), config).isPassed()).isTrue();
    }

    @Test
    void blocksLowConfidenceAndTooFewComparables() {
        PricingDecision unsure = decision("210.00");
        unsure.setConfidence(new BigDecimal("0.60"));
        PricingDecision thin = decision("210.00");
        thin.setComparableCount(2);

        assertBlocked(RepriceGuard.evaluate(active("200.00"), true, unsure, config), "BELOW_MINIMUM_CONFIDENCE");
        assertBlocked(RepriceGuard.evaluate(active("200.00"), true, thin, config), "BELOW_MINIMUM_COMPARABLE_COUNT");
    }

    @Test
    void blocksMovesAboveTheMaximumAmountAndPercent() {
        assertBlocked(RepriceGuard.evaluate(active("200.00"), true, decision("260.00"), config), "ABOVE_MAXIMUM_ABSOLUTE_DELTA");

        config.setMaximumAbsoluteDelta(null);
        // 60.00 on 100.00 is 60%, above 50%.
        assertBlocked(RepriceGuard.evaluate(active("100.00"), true, decision("160.00"), config), "ABOVE_MAXIMUM_PERCENT_DELTA");
    }

    @Test
    void blocksAnActiveListingWithNoLotButLetsAPricedDraftThrough() {
        assertBlocked(RepriceGuard.evaluate(active("200.00"), false, decision("210.00"), config), "NO_REMOTE_LISTING");

        MarketplaceListing pricedDraft = active("200.00");
        pricedDraft.setListingStatusCode("DRAFT");
        RepriceGuard.Result result = RepriceGuard.evaluate(pricedDraft, false, decision("210.00"), config);
        assertThat(result.isPassed()).isTrue();
        assertThat(result.initialPrice()).isFalse();
    }

    @Test
    void aDraftsFirstPriceIsGuardedByConfidenceAndComparablesOnly() {
        MarketplaceListing unpricedDraft = draft();
        // No current price, so no move limits apply: 900.00 would be far above the maximum move.
        RepriceGuard.Result result = RepriceGuard.evaluate(unpricedDraft, false, decision("900.00"), config);
        assertThat(result.isPassed()).isTrue();
        assertThat(result.initialPrice()).isTrue();

        PricingDecision thin = decision("900.00");
        thin.setComparableCount(1);
        assertBlocked(RepriceGuard.evaluate(unpricedDraft, false, thin, config), "BELOW_MINIMUM_COMPARABLE_COUNT");
    }

    @Test
    void anUnpricedDraftThatAlreadyHasALotIsBlocked() {
        assertBlocked(RepriceGuard.evaluate(draft(), true, decision("210.00"), config), "DRAFT_HAS_REMOTE_LISTING");
    }

    private static void assertBlocked(RepriceGuard.Result result, String reasonCode) {
        assertThat(result.isPassed()).isFalse();
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reasonCode()).isEqualTo(reasonCode);
    }

    private static MarketplaceListing active(String unitPrice) {
        return MarketplaceListing.builder()
                .marketplaceListingId(100)
                .listingStatusCode("ACTIVE")
                .externalListingId("12345")
                .unitPrice(unitPrice == null ? null : new BigDecimal(unitPrice))
                .currencyCode("USD")
                .fixedPrice(false)
                .build();
    }

    private static MarketplaceListing draft() {
        return MarketplaceListing.builder()
                .marketplaceListingId(101)
                .listingStatusCode("DRAFT")
                .currencyCode("USD")
                .fixedPrice(false)
                .build();
    }

    private static PricingDecision decision(String finalPrice) {
        return PricingDecision.builder()
                .decisionStatusCode("PROPOSED")
                .reasonCode("MARKET_POSITION")
                .finalPrice(finalPrice == null ? null : new BigDecimal(finalPrice))
                .currencyCode("USD")
                .confidence(new BigDecimal("0.90"))
                .comparableCount(6)
                .build();
    }
}
