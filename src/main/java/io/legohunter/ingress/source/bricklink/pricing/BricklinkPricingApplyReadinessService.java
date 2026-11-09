package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.PricingApplyReadinessDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.PricingApplyReadiness;
import io.legohunter.data.dto.PricingDecisionReview;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.pricing.apply-readiness", name = "enabled", havingValue = "true")
public class BricklinkPricingApplyReadinessService {
    static final String READY_TO_APPLY = "READY_TO_APPLY";
    static final String READY_TO_APPLY_INITIAL_PRICE = "READY_TO_APPLY_INITIAL_PRICE";
    private static final String LISTING_STATUS_DRAFT = "DRAFT";

    private final BricklinkPricingApplyReadinessProperties properties;
    private final PricingDecisionDao pricingDecisionDao;
    private final PricingApplyReadinessDao pricingApplyReadinessDao;

    public BricklinkPricingApplyReadinessResult runOnce() {
        long start = System.currentTimeMillis();
        Set<PricingDecisionReview> reviews = pricingDecisionDao
                .findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodesAndDecisionStatusCode(
                        properties.getBricklinkExternalServiceId(),
                        properties.effectivePriceableListingStatusCodes(),
                        properties.effectiveProposedDecisionStatusCode(),
                        properties.effectiveBatchSize()
                );

        if (reviews.isEmpty()) {
            return BricklinkPricingApplyReadinessResult.noWork(elapsedMillis(start));
        }

        ApplyReadinessCounters counters = new ApplyReadinessCounters(reviews.size());
        for (PricingDecisionReview review : reviews) {
            ApplyReadinessEvaluation evaluation = evaluate(review);
            counters.record(evaluation.status());
            persistReadiness(review, evaluation);
            if (evaluation.status().readyToApply()) {
                log.debug(
                        "bricklink.pricing.apply_readiness.ready marketplaceListingId={} pricingDecisionId={} readinessStatus={} initialPrice={} externalListingId={} currentPrice={} proposedPrice={} delta={} currencyCode={} reasonCode={} algorithmVersion={} confidence={} comparableCount={}",
                        review.getMarketplaceListingId(),
                        review.getPricingDecisionId(),
                        evaluation.status().readinessStatusCode(),
                        isInitialPriceCandidate(review),
                        review.getExternalListingId(),
                        money(review.getCurrentUnitPrice()),
                        money(review.getFinalPrice()),
                        money(evaluation.deltaAmount()),
                        decisionCurrencyCode(review),
                        review.getReasonCode(),
                        review.getAlgorithmVersion(),
                        review.getConfidence(),
                        review.getComparableCount()
                );
            } else {
                log.debug(
                        "bricklink.pricing.apply_readiness.skipped marketplaceListingId={} pricingDecisionId={} status={} reasonCode={} currentPrice={} proposedPrice={} confidence={} comparableCount={}",
                        review.getMarketplaceListingId(),
                        review.getPricingDecisionId(),
                        evaluation.status(),
                        review.getReasonCode(),
                        money(review.getCurrentUnitPrice()),
                        money(review.getFinalPrice()),
                        review.getConfidence(),
                        review.getComparableCount()
                );
            }
        }

        return counters.result(elapsedMillis(start));
    }

    private ApplyReadinessEvaluation evaluate(PricingDecisionReview review) {
        if (Boolean.TRUE.equals(review.getFixedPrice())) {
            return evaluation(ApplyReadinessStatus.SKIPPED_FIXED_PRICE);
        }
        if (Boolean.TRUE.equals(review.getNewerSnapshotAvailable())) {
            return evaluation(ApplyReadinessStatus.SKIPPED_STALE_DECISION);
        }
        if (!properties.effectiveProposedDecisionStatusCode().equals(cleanStatusCode(review.getDecisionStatusCode()))) {
            return evaluation(ApplyReadinessStatus.SKIPPED_UNSUPPORTED_DECISION_STATUS);
        }
        if (review.getFinalPrice() == null) {
            return evaluation(ApplyReadinessStatus.SKIPPED_MISSING_FINAL_PRICE);
        }
        if (review.getFinalPrice().signum() <= 0) {
            return evaluation(ApplyReadinessStatus.SKIPPED_MISSING_FINAL_PRICE);
        }
        boolean initialPriceCandidate = isInitialPriceCandidate(review);
        if (!initialPriceCandidate && review.getCurrentUnitPrice() == null) {
            return evaluation(ApplyReadinessStatus.SKIPPED_MISSING_CURRENT_PRICE);
        }
        if (!sameCurrency(review)) {
            return evaluation(ApplyReadinessStatus.SKIPPED_CURRENCY_MISMATCH);
        }

        String reasonCode = cleanReasonCode(review.getReasonCode());
        if (properties.effectiveBlockedReasonCodes().contains(reasonCode)) {
            return evaluation(ApplyReadinessStatus.SKIPPED_BLOCKED_REASON_CODE);
        }
        if (!properties.effectiveApplyEligibleReasonCodes().contains(reasonCode)) {
            return evaluation(ApplyReadinessStatus.SKIPPED_INELIGIBLE_REASON);
        }

        if (initialPriceCandidate) {
            if (confidence(review).compareTo(properties.effectiveMinimumConfidence()) < 0) {
                return evaluation(ApplyReadinessStatus.SKIPPED_BELOW_MINIMUM_CONFIDENCE);
            }
            if (comparableCount(review) < properties.effectiveMinimumComparableCount()) {
                return evaluation(ApplyReadinessStatus.SKIPPED_BELOW_MINIMUM_COMPARABLE_COUNT);
            }
            return evaluation(ApplyReadinessStatus.READY_TO_APPLY_INITIAL_PRICE);
        }

        BigDecimal absoluteDelta = delta(review);
        if (absoluteDelta.compareTo(properties.effectiveMinimumPriceDelta()) < 0) {
            return evaluation(ApplyReadinessStatus.SKIPPED_BELOW_MINIMUM_DELTA, absoluteDelta, percentDeltaOrNull(review, absoluteDelta), properties.effectiveMinimumPriceDelta());
        }

        BigDecimal minimumPercentDelta = minimumPercentDelta(review);
        if (minimumPercentDelta != null && absoluteDelta.compareTo(minimumPercentDelta) < 0) {
            return evaluation(
                    ApplyReadinessStatus.SKIPPED_BELOW_MINIMUM_DELTA_PERCENT,
                    absoluteDelta,
                    percentDelta(review, absoluteDelta),
                    minimumPercentDelta
            );
        }
        if (confidence(review).compareTo(properties.effectiveMinimumConfidence()) < 0) {
            return evaluation(ApplyReadinessStatus.SKIPPED_BELOW_MINIMUM_CONFIDENCE, absoluteDelta, percentDeltaOrNull(review, absoluteDelta), minimumPercentDelta);
        }
        if (comparableCount(review) < properties.effectiveMinimumComparableCount()) {
            return evaluation(ApplyReadinessStatus.SKIPPED_BELOW_MINIMUM_COMPARABLE_COUNT, absoluteDelta, percentDeltaOrNull(review, absoluteDelta), minimumPercentDelta);
        }

        BigDecimal maximumAbsoluteDelta = properties.effectiveMaximumAbsoluteDelta();
        if (maximumAbsoluteDelta != null && absoluteDelta.compareTo(maximumAbsoluteDelta) > 0) {
            return evaluation(ApplyReadinessStatus.SKIPPED_ABOVE_MAXIMUM_ABSOLUTE_DELTA, absoluteDelta, percentDeltaOrNull(review, absoluteDelta), minimumPercentDelta);
        }

        BigDecimal maximumPercentDelta = properties.effectiveMaximumPercentDelta();
        if (maximumPercentDelta != null) {
            if (review.getCurrentUnitPrice().signum() <= 0) {
                return evaluation(ApplyReadinessStatus.SKIPPED_MISSING_CURRENT_PRICE, absoluteDelta, null, minimumPercentDelta);
            }
            if (percentDelta(review, absoluteDelta).compareTo(maximumPercentDelta) > 0) {
                return evaluation(ApplyReadinessStatus.SKIPPED_ABOVE_MAXIMUM_PERCENT_DELTA, absoluteDelta, percentDelta(review, absoluteDelta), minimumPercentDelta);
            }
        }

        return evaluation(ApplyReadinessStatus.READY_TO_APPLY, absoluteDelta, percentDeltaOrNull(review, absoluteDelta), minimumPercentDelta);
    }

    private boolean isInitialPriceCandidate(PricingDecisionReview review) {
        return LISTING_STATUS_DRAFT.equals(cleanStatusCode(review.getListingStatusCode()))
                && review.getCurrentUnitPrice() == null
                && blank(review.getExternalListingId());
    }

    private void persistReadiness(PricingDecisionReview review, ApplyReadinessEvaluation evaluation) {
        pricingApplyReadinessDao.upsert(PricingApplyReadiness.builder()
                .marketplaceListingId(review.getMarketplaceListingId())
                .pricingDecisionId(review.getPricingDecisionId())
                .pricingSnapshotId(review.getPricingSnapshotId())
                .marketSnapshotId(review.getMarketSnapshotId())
                .marketSnapshotCapturedAt(review.getSnapshotCapturedAt())
                .readinessStatusCode(evaluation.status().readinessStatusCode())
                .blockReasonCode(evaluation.status().blockReasonCode())
                .currentPrice(money(review.getCurrentUnitPrice()))
                .proposedPrice(money(review.getFinalPrice()))
                .deltaAmount(money(evaluation.deltaAmount()))
                .deltaPercent(scalePercent(evaluation.deltaPercent()))
                .minimumRequiredDelta(money(evaluation.minimumRequiredDelta()))
                .currencyCode(decisionCurrencyCode(review))
                .confidence(review.getConfidence())
                .comparableCount(review.getComparableCount())
                .evaluatedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .build());
    }

    private boolean sameCurrency(PricingDecisionReview review) {
        return currentCurrencyCode(review).equals(decisionCurrencyCode(review));
    }

    private String currentCurrencyCode(PricingDecisionReview review) {
        return currencyCode(review.getCurrentCurrencyCode());
    }

    private String decisionCurrencyCode(PricingDecisionReview review) {
        return currencyCode(review.getDecisionCurrencyCode() == null ? review.getCurrencyCode() : review.getDecisionCurrencyCode());
    }

    private String currencyCode(String value) {
        if (value == null || value.isBlank()) {
            return "USD";
        }
        return value.trim().toUpperCase();
    }

    private String cleanReasonCode(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "";
        }
        return reasonCode.trim().toUpperCase();
    }

    private String cleanStatusCode(String statusCode) {
        if (statusCode == null || statusCode.isBlank()) {
            return "";
        }
        return statusCode.trim().toUpperCase();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BigDecimal delta(PricingDecisionReview review) {
        return review.getFinalPrice().subtract(review.getCurrentUnitPrice()).abs();
    }

    private BigDecimal percentDelta(PricingDecisionReview review, BigDecimal absoluteDelta) {
        return absoluteDelta.divide(review.getCurrentUnitPrice().abs(), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal percentDeltaOrNull(PricingDecisionReview review, BigDecimal absoluteDelta) {
        if (review.getCurrentUnitPrice() == null || review.getCurrentUnitPrice().signum() <= 0) {
            return null;
        }
        return percentDelta(review, absoluteDelta);
    }

    private BigDecimal minimumPercentDelta(PricingDecisionReview review) {
        if (!properties.isMinimumDeltaPercentEnabled()) {
            return null;
        }
        if (review.getCurrentUnitPrice() == null || review.getCurrentUnitPrice().signum() <= 0) {
            return null;
        }
        BigDecimal percent = properties.effectiveMinimumDeltaPercent();
        if (percent.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return review.getCurrentUnitPrice().abs()
                .multiply(percent)
                .setScale(2, RoundingMode.CEILING);
    }

    private BigDecimal confidence(PricingDecisionReview review) {
        return review.getConfidence() == null ? BigDecimal.ZERO : review.getConfidence();
    }

    private int comparableCount(PricingDecisionReview review) {
        return review.getComparableCount() == null ? 0 : review.getComparableCount();
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal scalePercent(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
    }

    private ApplyReadinessEvaluation evaluation(ApplyReadinessStatus status) {
        return evaluation(status, null, null, null);
    }

    private ApplyReadinessEvaluation evaluation(
            ApplyReadinessStatus status,
            BigDecimal deltaAmount,
            BigDecimal deltaPercent,
            BigDecimal minimumRequiredDelta
    ) {
        return new ApplyReadinessEvaluation(status, deltaAmount, deltaPercent, minimumRequiredDelta);
    }

    private long elapsedMillis(long start) {
        return System.currentTimeMillis() - start;
    }

    private enum ApplyReadinessStatus {
        READY_TO_APPLY(BricklinkPricingApplyReadinessService.READY_TO_APPLY, null),
        READY_TO_APPLY_INITIAL_PRICE(BricklinkPricingApplyReadinessService.READY_TO_APPLY_INITIAL_PRICE, null),
        SKIPPED_FIXED_PRICE("BLOCKED_FIXED_PRICE", "FIXED_PRICE"),
        SKIPPED_MISSING_CURRENT_PRICE("BLOCKED_MISSING_CURRENT_PRICE", "MISSING_CURRENT_PRICE"),
        SKIPPED_MISSING_FINAL_PRICE("BLOCKED_MISSING_FINAL_PRICE", "MISSING_FINAL_PRICE"),
        SKIPPED_CURRENCY_MISMATCH("BLOCKED_CURRENCY_MISMATCH", "CURRENCY_MISMATCH"),
        SKIPPED_UNSUPPORTED_DECISION_STATUS("BLOCKED_UNSUPPORTED_DECISION_STATUS", "UNSUPPORTED_DECISION_STATUS"),
        SKIPPED_BLOCKED_REASON_CODE("BLOCKED_REASON_CODE", "BLOCKED_REASON_CODE"),
        SKIPPED_INELIGIBLE_REASON("BLOCKED_INELIGIBLE_REASON", "INELIGIBLE_REASON"),
        SKIPPED_BELOW_MINIMUM_DELTA("BLOCKED_BELOW_MINIMUM_DELTA", "BELOW_MINIMUM_DELTA"),
        SKIPPED_BELOW_MINIMUM_DELTA_PERCENT("BLOCKED_BELOW_MINIMUM_DELTA_PERCENT", "BELOW_MINIMUM_DELTA_PERCENT"),
        SKIPPED_BELOW_MINIMUM_CONFIDENCE("BLOCKED_BELOW_MINIMUM_CONFIDENCE", "BELOW_MINIMUM_CONFIDENCE"),
        SKIPPED_BELOW_MINIMUM_COMPARABLE_COUNT("BLOCKED_BELOW_MINIMUM_COMPARABLE_COUNT", "BELOW_MINIMUM_COMPARABLE_COUNT"),
        SKIPPED_ABOVE_MAXIMUM_ABSOLUTE_DELTA("BLOCKED_ABOVE_MAXIMUM_ABSOLUTE_DELTA", "ABOVE_MAXIMUM_ABSOLUTE_DELTA"),
        SKIPPED_ABOVE_MAXIMUM_PERCENT_DELTA("BLOCKED_ABOVE_MAXIMUM_PERCENT_DELTA", "ABOVE_MAXIMUM_PERCENT_DELTA"),
        SKIPPED_STALE_DECISION("BLOCKED_STALE_DECISION", "STALE_DECISION");

        private final String readinessStatusCode;
        private final String blockReasonCode;

        ApplyReadinessStatus(String readinessStatusCode, String blockReasonCode) {
            this.readinessStatusCode = readinessStatusCode;
            this.blockReasonCode = blockReasonCode;
        }

        private String readinessStatusCode() {
            return readinessStatusCode;
        }

        private String blockReasonCode() {
            return blockReasonCode;
        }

        private boolean readyToApply() {
            return this == READY_TO_APPLY || this == READY_TO_APPLY_INITIAL_PRICE;
        }
    }

    private record ApplyReadinessEvaluation(
            ApplyReadinessStatus status,
            BigDecimal deltaAmount,
            BigDecimal deltaPercent,
            BigDecimal minimumRequiredDelta
    ) {
    }

    private static final class ApplyReadinessCounters {
        private final int decisionsSelected;
        private int readyToApply;
        private int skippedFixedPrice;
        private int skippedMissingCurrentPrice;
        private int skippedMissingFinalPrice;
        private int skippedCurrencyMismatch;
        private int skippedUnsupportedDecisionStatus;
        private int skippedBlockedReasonCode;
        private int skippedIneligibleReason;
        private int skippedBelowMinimumDelta;
        private int skippedBelowMinimumDeltaPercent;
        private int skippedBelowMinimumConfidence;
        private int skippedBelowMinimumComparableCount;
        private int skippedAboveMaximumAbsoluteDelta;
        private int skippedAboveMaximumPercentDelta;
        private int skippedStaleDecision;

        private ApplyReadinessCounters(int decisionsSelected) {
            this.decisionsSelected = decisionsSelected;
        }

        private void record(ApplyReadinessStatus status) {
            switch (status) {
                case READY_TO_APPLY -> readyToApply++;
                case READY_TO_APPLY_INITIAL_PRICE -> readyToApply++;
                case SKIPPED_FIXED_PRICE -> skippedFixedPrice++;
                case SKIPPED_MISSING_CURRENT_PRICE -> skippedMissingCurrentPrice++;
                case SKIPPED_MISSING_FINAL_PRICE -> skippedMissingFinalPrice++;
                case SKIPPED_CURRENCY_MISMATCH -> skippedCurrencyMismatch++;
                case SKIPPED_UNSUPPORTED_DECISION_STATUS -> skippedUnsupportedDecisionStatus++;
                case SKIPPED_BLOCKED_REASON_CODE -> skippedBlockedReasonCode++;
                case SKIPPED_INELIGIBLE_REASON -> skippedIneligibleReason++;
                case SKIPPED_BELOW_MINIMUM_DELTA -> skippedBelowMinimumDelta++;
                case SKIPPED_BELOW_MINIMUM_DELTA_PERCENT -> skippedBelowMinimumDeltaPercent++;
                case SKIPPED_BELOW_MINIMUM_CONFIDENCE -> skippedBelowMinimumConfidence++;
                case SKIPPED_BELOW_MINIMUM_COMPARABLE_COUNT -> skippedBelowMinimumComparableCount++;
                case SKIPPED_ABOVE_MAXIMUM_ABSOLUTE_DELTA -> skippedAboveMaximumAbsoluteDelta++;
                case SKIPPED_ABOVE_MAXIMUM_PERCENT_DELTA -> skippedAboveMaximumPercentDelta++;
                case SKIPPED_STALE_DECISION -> skippedStaleDecision++;
            }
        }

        private BricklinkPricingApplyReadinessResult result(long elapsedMillis) {
            return new BricklinkPricingApplyReadinessResult(
                    readyToApply > 0 ? "SUCCESS" : "NO_READY_DECISIONS",
                    decisionsSelected,
                    readyToApply,
                    skippedFixedPrice,
                    skippedMissingCurrentPrice,
                    skippedMissingFinalPrice,
                    skippedCurrencyMismatch,
                    skippedUnsupportedDecisionStatus,
                    skippedBlockedReasonCode,
                    skippedIneligibleReason,
                    skippedBelowMinimumDelta,
                    skippedBelowMinimumDeltaPercent,
                    skippedBelowMinimumConfidence,
                    skippedBelowMinimumComparableCount,
                    skippedAboveMaximumAbsoluteDelta,
                    skippedAboveMaximumPercentDelta,
                    skippedStaleDecision,
                    elapsedMillis
            );
        }
    }
}
