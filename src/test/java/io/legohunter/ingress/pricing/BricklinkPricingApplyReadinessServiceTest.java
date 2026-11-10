package io.legohunter.ingress.pricing;

import io.legohunter.data.dao.PricingApplyReadinessDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.PricingApplyReadiness;
import io.legohunter.data.dto.PricingDecisionReview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingApplyReadinessServiceTest {
    private PricingDecisionDao pricingDecisionDao;
    private PricingApplyReadinessDao pricingApplyReadinessDao;
    private BricklinkPricingApplyReadinessProperties properties;
    private BricklinkPricingApplyReadinessService service;

    @BeforeEach
    void setUp() {
        pricingDecisionDao = mock(PricingDecisionDao.class);
        pricingApplyReadinessDao = mock(PricingApplyReadinessDao.class);
        properties = new BricklinkPricingApplyReadinessProperties();
        properties.setEnabled(true);
        properties.setBatchSize(10);
        properties.setMinimumComparableCount(0);
        service = new BricklinkPricingApplyReadinessService(properties, pricingDecisionDao, pricingApplyReadinessDao);
    }

    @Test
    void runOnceReturnsNoWorkWhenNoLatestProposedDecisionsExist() {
        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodeAndDecisionStatusCode(
                2, "ACTIVE", "PROPOSED", 10
        )).thenReturn(Set.of());
        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodesAndDecisionStatusCode(
                2, Set.of("ACTIVE", "DRAFT"), "PROPOSED", 10
        )).thenReturn(Set.of());

        BricklinkPricingApplyReadinessResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_WORK");
        assertThat(result.decisionsSelected()).isZero();
        assertThat(result.readyToApply()).isZero();
        verify(pricingDecisionDao).findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodesAndDecisionStatusCode(
                2, Set.of("ACTIVE", "DRAFT"), "PROPOSED", 10
        );
    }

    @Test
    void runOnceCountsReadyDecisionWhenPriceDeltaMeetsThreshold() {
        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodesAndDecisionStatusCode(
                2, Set.of("ACTIVE", "DRAFT"), "PROPOSED", 10
        )).thenReturn(Set.of(review("100.00", "95.00", BricklinkPricingDecisionService.REASON_MARKET_POSITION)));

        BricklinkPricingApplyReadinessResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.decisionsSelected()).isOne();
        assertThat(result.readyToApply()).isOne();
        assertThat(result.skippedFixedPrice()).isZero();
        assertThat(result.skippedMissingCurrentPrice()).isZero();
        assertThat(result.skippedMissingFinalPrice()).isZero();
        assertThat(result.skippedCurrencyMismatch()).isZero();
        assertThat(result.skippedIneligibleReason()).isZero();
        assertThat(result.skippedBelowMinimumDelta()).isZero();
        ArgumentCaptor<PricingApplyReadiness> readinessCaptor = ArgumentCaptor.forClass(PricingApplyReadiness.class);
        verify(pricingApplyReadinessDao).upsert(readinessCaptor.capture());
        assertThat(readinessCaptor.getValue().getReadinessStatusCode()).isEqualTo("READY_TO_APPLY");
        assertThat(readinessCaptor.getValue().getDeltaAmount()).isEqualByComparingTo("5.00");
    }

    @Test
    void runOnceAllowsPricingPlaneToSetTheInitialPriceForAnUnpricedDraft() {
        PricingDecisionReview initialPrice = review(null, "7.19", BricklinkPricingDecisionService.REASON_SINGLE_COMPARABLE_DISCOUNTED);
        initialPrice.setListingStatusCode("DRAFT");
        initialPrice.setExternalListingId(null);
        initialPrice.setComparableCount(15);
        initialPrice.setConfidence(new BigDecimal("0.90"));
        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodesAndDecisionStatusCode(
                2, Set.of("ACTIVE", "DRAFT"), "PROPOSED", 10
        )).thenReturn(Set.of(initialPrice));

        BricklinkPricingApplyReadinessResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.decisionsSelected()).isOne();
        assertThat(result.readyToApply()).isOne();
        ArgumentCaptor<PricingApplyReadiness> readinessCaptor = ArgumentCaptor.forClass(PricingApplyReadiness.class);
        verify(pricingApplyReadinessDao).upsert(readinessCaptor.capture());
        assertThat(readinessCaptor.getValue().getReadinessStatusCode()).isEqualTo("READY_TO_APPLY_INITIAL_PRICE");
        assertThat(readinessCaptor.getValue().getCurrentPrice()).isNull();
        assertThat(readinessCaptor.getValue().getProposedPrice()).isEqualByComparingTo("7.19");
        assertThat(readinessCaptor.getValue().getDeltaAmount()).isNull();
        assertThat(readinessCaptor.getValue().getDeltaPercent()).isNull();
    }

    @Test
    void runOnceSeparatesAllSkipBuckets() {
        PricingDecisionReview fixedPrice = review("100.00", "95.00", BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        fixedPrice.setFixedPrice(true);
        PricingDecisionReview missingCurrentPrice = review(null, "95.00", BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        PricingDecisionReview missingFinalPrice = review("100.00", null, BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        PricingDecisionReview currencyMismatch = review("100.00", "95.00", BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        currencyMismatch.setDecisionCurrencyCode("CAD");
        PricingDecisionReview ineligibleReason = review("100.00", "95.00", BricklinkPricingDecisionService.REASON_NO_EXACT_COMPARABLES);
        PricingDecisionReview belowMinimumDelta = review("100.00", "100.00", BricklinkPricingDecisionService.REASON_MARKET_POSITION);

        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodesAndDecisionStatusCode(
                2, Set.of("ACTIVE", "DRAFT"), "PROPOSED", 10
        )).thenReturn(Set.of(fixedPrice, missingCurrentPrice, missingFinalPrice, currencyMismatch, ineligibleReason, belowMinimumDelta));

        BricklinkPricingApplyReadinessResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_READY_DECISIONS");
        assertThat(result.decisionsSelected()).isEqualTo(6);
        assertThat(result.readyToApply()).isZero();
        assertThat(result.skippedFixedPrice()).isOne();
        assertThat(result.skippedMissingCurrentPrice()).isOne();
        assertThat(result.skippedMissingFinalPrice()).isOne();
        assertThat(result.skippedCurrencyMismatch()).isOne();
        assertThat(result.skippedIneligibleReason()).isOne();
        assertThat(result.skippedBelowMinimumDelta()).isOne();
    }

    @Test
    void runOnceBlocksWhenPercentMinimumDeltaIsNotMetAndRoundsRequiredDeltaUpToPenny() {
        properties.getMinimumDelta().setEnabled(true);
        properties.getMinimumDelta().setPercent(new BigDecimal("0.02"));
        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodesAndDecisionStatusCode(
                2, Set.of("ACTIVE", "DRAFT"), "PROPOSED", 10
        )).thenReturn(Set.of(review("4.99", "4.90", BricklinkPricingDecisionService.REASON_MARKET_POSITION)));

        BricklinkPricingApplyReadinessResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_READY_DECISIONS");
        assertThat(result.skippedBelowMinimumDeltaPercent()).isOne();
        ArgumentCaptor<PricingApplyReadiness> readinessCaptor = ArgumentCaptor.forClass(PricingApplyReadiness.class);
        verify(pricingApplyReadinessDao).upsert(readinessCaptor.capture());
        assertThat(readinessCaptor.getValue().getReadinessStatusCode()).isEqualTo("BLOCKED_BELOW_MINIMUM_DELTA_PERCENT");
        assertThat(readinessCaptor.getValue().getBlockReasonCode()).isEqualTo("BELOW_MINIMUM_DELTA_PERCENT");
        assertThat(readinessCaptor.getValue().getMinimumRequiredDelta()).isEqualByComparingTo("0.10");
    }

    @Test
    void runOnceAllowsPercentMinimumDeltaAtExactThreshold() {
        properties.getMinimumDelta().setEnabled(true);
        properties.getMinimumDelta().setPercent(new BigDecimal("0.02"));
        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodesAndDecisionStatusCode(
                2, Set.of("ACTIVE", "DRAFT"), "PROPOSED", 10
        )).thenReturn(Set.of(review("586.00", "574.28", BricklinkPricingDecisionService.REASON_MARKET_POSITION)));

        BricklinkPricingApplyReadinessResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.readyToApply()).isOne();
        assertThat(result.skippedBelowMinimumDeltaPercent()).isZero();
    }

    @Test
    void runOnceBlocksStaleDecisionWhenNewerSnapshotExists() {
        PricingDecisionReview stale = review("100.00", "95.00", BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        stale.setNewerSnapshotAvailable(true);
        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodesAndDecisionStatusCode(
                2, Set.of("ACTIVE", "DRAFT"), "PROPOSED", 10
        )).thenReturn(Set.of(stale));

        BricklinkPricingApplyReadinessResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_READY_DECISIONS");
        assertThat(result.skippedStaleDecision()).isOne();
        ArgumentCaptor<PricingApplyReadiness> readinessCaptor = ArgumentCaptor.forClass(PricingApplyReadiness.class);
        verify(pricingApplyReadinessDao).upsert(readinessCaptor.capture());
        assertThat(readinessCaptor.getValue().getReadinessStatusCode()).isEqualTo("BLOCKED_STALE_DECISION");
        assertThat(readinessCaptor.getValue().getBlockReasonCode()).isEqualTo("STALE_DECISION");
    }

    @Test
    void runOnceUsesConfiguredCandidateSelectionAndMinimumDelta() {
        properties.setBricklinkExternalServiceId(7);
        properties.setActiveListingStatusCode("active");
        properties.setProposedDecisionStatusCode("proposed");
        properties.setBatchSize(0);
        properties.setMinimumPriceDelta(new BigDecimal("10.00"));
        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodesAndDecisionStatusCode(
                7, Set.of("ACTIVE", "DRAFT"), "PROPOSED", 1
        )).thenReturn(Set.of(review("100.00", "95.00", BricklinkPricingDecisionService.REASON_MARKET_POSITION)));

        BricklinkPricingApplyReadinessResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_READY_DECISIONS");
        assertThat(result.skippedBelowMinimumDelta()).isOne();
        verify(pricingDecisionDao).findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodesAndDecisionStatusCode(
                7, Set.of("ACTIVE", "DRAFT"), "PROPOSED", 1
        );
    }

    @Test
    void runOnceSeparatesPhaseEightGuardrailBuckets() {
        properties.setMinimumConfidence(new BigDecimal("0.75"));
        properties.setMinimumComparableCount(3);
        properties.setMaximumAbsoluteDelta(new BigDecimal("50.00"));
        properties.setMaximumPercentDelta(new BigDecimal("0.50"));
        properties.setBlockedReasonCodes(Set.of(BricklinkPricingDecisionService.REASON_SINGLE_COMPARABLE_DISCOUNTED));

        PricingDecisionReview unsupportedStatus = review("100.00", "95.00", BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        unsupportedStatus.setDecisionStatusCode("FAILED");
        PricingDecisionReview blockedReason = review("100.00", "95.00", BricklinkPricingDecisionService.REASON_SINGLE_COMPARABLE_DISCOUNTED);
        PricingDecisionReview lowConfidence = review("100.00", "95.00", BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        lowConfidence.setConfidence(new BigDecimal("0.50"));
        PricingDecisionReview lowComparableCount = review("100.00", "95.00", BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        lowComparableCount.setComparableCount(2);
        PricingDecisionReview aboveAbsoluteDelta = review("200.00", "90.00", BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        PricingDecisionReview abovePercentDelta = review("100.00", "40.00", BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        properties.setMaximumAbsoluteDelta(new BigDecimal("100.00"));

        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodesAndDecisionStatusCode(
                2, Set.of("ACTIVE", "DRAFT"), "PROPOSED", 10
        )).thenReturn(Set.of(unsupportedStatus, blockedReason, lowConfidence, lowComparableCount, aboveAbsoluteDelta, abovePercentDelta));

        BricklinkPricingApplyReadinessResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_READY_DECISIONS");
        assertThat(result.decisionsSelected()).isEqualTo(6);
        assertThat(result.skippedUnsupportedDecisionStatus()).isOne();
        assertThat(result.skippedBlockedReasonCode()).isOne();
        assertThat(result.skippedBelowMinimumConfidence()).isOne();
        assertThat(result.skippedBelowMinimumComparableCount()).isOne();
        assertThat(result.skippedAboveMaximumAbsoluteDelta()).isOne();
        assertThat(result.skippedAboveMaximumPercentDelta()).isOne();
    }

    private PricingDecisionReview review(String currentPrice, String finalPrice, String reasonCode) {
        return PricingDecisionReview.builder()
                .marketplaceListingId(10)
                .externalListingId("BL-INV-10")
                .listingStatusCode("ACTIVE")
                .currentUnitPrice(currentPrice == null ? null : new BigDecimal(currentPrice))
                .currentCurrencyCode("USD")
                .fixedPrice(false)
                .pricingDecisionId(900L)
                .decisionStatusCode("PROPOSED")
                .reasonCode(reasonCode)
                .finalPrice(finalPrice == null ? null : new BigDecimal(finalPrice))
                .currencyCode("USD")
                .decisionCurrencyCode("USD")
                .algorithmVersion("bricklink-market-position-v2")
                .comparableCount(5)
                .confidence(new BigDecimal("0.90"))
                .build();
    }
}
