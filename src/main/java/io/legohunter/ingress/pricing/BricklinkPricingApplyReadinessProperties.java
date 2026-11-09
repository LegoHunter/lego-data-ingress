package io.legohunter.ingress.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.pricing.apply-readiness")
public class BricklinkPricingApplyReadinessProperties {
    private boolean enabled = false;
    private Integer bricklinkExternalServiceId = 2;
    private Set<String> priceableListingStatusCodes = Set.of("ACTIVE", "DRAFT");
    private String activeListingStatusCode = "ACTIVE";
    private String proposedDecisionStatusCode = BricklinkPricingDecisionService.STATUS_PROPOSED;
    private int batchSize = 25;
    private BigDecimal minimumPriceDelta = new BigDecimal("0.01");
    private MinimumDelta minimumDelta = new MinimumDelta();
    private BigDecimal minimumConfidence = BigDecimal.ZERO;
    private int minimumComparableCount = 1;
    private BigDecimal maximumAbsoluteDelta;
    private BigDecimal maximumPercentDelta;
    private Set<String> blockedReasonCodes = new LinkedHashSet<>();
    private Set<String> applyEligibleReasonCodes = new LinkedHashSet<>(Set.of(
            BricklinkPricingDecisionService.REASON_MARKET_POSITION,
            BricklinkPricingDecisionService.REASON_SINGLE_COMPARABLE_DISCOUNTED,
            BricklinkPricingDecisionService.REASON_TWO_COMPARABLES_WEIGHTED,
            BricklinkPricingDecisionService.REASON_BELOW_MIN_PRICE_CLAMPED,
            BricklinkPricingDecisionService.REASON_ABOVE_MAX_PRICE_CLAMPED
    ));
    private Scheduled scheduled = new Scheduled();

    public String effectiveActiveListingStatusCode() {
        if (activeListingStatusCode == null || activeListingStatusCode.isBlank()) {
            return "ACTIVE";
        }
        return activeListingStatusCode.trim().toUpperCase();
    }

    public Set<String> effectivePriceableListingStatusCodes() {
        if (priceableListingStatusCodes == null || priceableListingStatusCodes.isEmpty()) {
            return Set.of(effectiveActiveListingStatusCode());
        }
        Set<String> normalizedStatusCodes = priceableListingStatusCodes.stream()
                .filter(statusCode -> statusCode != null && !statusCode.isBlank())
                .map(statusCode -> statusCode.trim().toUpperCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return normalizedStatusCodes.isEmpty() ? Set.of(effectiveActiveListingStatusCode()) : normalizedStatusCodes;
    }

    public String effectiveProposedDecisionStatusCode() {
        if (proposedDecisionStatusCode == null || proposedDecisionStatusCode.isBlank()) {
            return BricklinkPricingDecisionService.STATUS_PROPOSED;
        }
        return proposedDecisionStatusCode.trim().toUpperCase();
    }

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public BigDecimal effectiveMinimumPriceDelta() {
        if (minimumPriceDelta == null || minimumPriceDelta.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return minimumPriceDelta;
    }

    public boolean isMinimumDeltaPercentEnabled() {
        return minimumDelta != null && minimumDelta.enabled;
    }

    public BigDecimal effectiveMinimumDeltaPercent() {
        if (minimumDelta == null || minimumDelta.percent == null || minimumDelta.percent.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return minimumDelta.percent;
    }

    public BigDecimal effectiveMinimumConfidence() {
        if (minimumConfidence == null || minimumConfidence.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return minimumConfidence;
    }

    public int effectiveMinimumComparableCount() {
        return Math.max(0, minimumComparableCount);
    }

    public BigDecimal effectiveMaximumAbsoluteDelta() {
        if (maximumAbsoluteDelta == null || maximumAbsoluteDelta.signum() < 0) {
            return null;
        }
        return maximumAbsoluteDelta;
    }

    public BigDecimal effectiveMaximumPercentDelta() {
        if (maximumPercentDelta == null || maximumPercentDelta.signum() < 0) {
            return null;
        }
        return maximumPercentDelta;
    }

    public Set<String> effectiveBlockedReasonCodes() {
        return normalizeReasonCodes(blockedReasonCodes);
    }

    public Set<String> effectiveApplyEligibleReasonCodes() {
        return normalizeReasonCodes(applyEligibleReasonCodes);
    }

    private Set<String> normalizeReasonCodes(Set<String> reasonCodes) {
        return reasonCodes == null ? Set.of() : reasonCodes.stream()
                .filter(reasonCode -> reasonCode != null && !reasonCode.isBlank())
                .map(reasonCode -> reasonCode.trim().toUpperCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Getter
    @Setter
    public static class MinimumDelta {
        private boolean enabled = false;
        private BigDecimal percent = new BigDecimal("0.02");
    }

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        private long fixedDelayMs = 300_000L;
        private long initialDelayMs = 30_000L;
        private String lockAtMostFor = "10m";
        private String lockAtLeastFor = "0s";
    }
}
