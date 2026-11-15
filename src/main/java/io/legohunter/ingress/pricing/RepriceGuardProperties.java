package io.legohunter.ingress.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Reprice guard ({@code lego.pricing.reprice.guard}, HOMELAB-66): whether a decision may be sent to the
 * marketplace. A decision the guard blocks is recorded BLOCKED with the reason. See {@link RepriceGuard}.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.pricing.reprice.guard")
public class RepriceGuardProperties {
    private BigDecimal minimumPriceDelta = new BigDecimal("0.01");
    private MinimumDelta minimumDelta = new MinimumDelta();
    private BigDecimal minimumConfidence = BigDecimal.ZERO;
    private int minimumComparableCount = 1;
    private BigDecimal maximumAbsoluteDelta;
    private BigDecimal maximumPercentDelta;
    private Set<String> blockedReasonCodes = new LinkedHashSet<>();
    /** Decision reasons that may be sent. A decision for any other reason is BLOCKED (INELIGIBLE_REASON). */
    private Set<String> eligibleReasonCodes = new LinkedHashSet<>(Set.of(
            BricklinkPricingDecider.REASON_MARKET_POSITION,
            BricklinkPricingDecider.REASON_SINGLE_COMPARABLE_DISCOUNTED,
            BricklinkPricingDecider.REASON_TWO_COMPARABLES_WEIGHTED,
            BricklinkPricingDecider.REASON_BELOW_MIN_PRICE_CLAMPED,
            BricklinkPricingDecider.REASON_ABOVE_MAX_PRICE_CLAMPED
    ));

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

    public Set<String> effectiveEligibleReasonCodes() {
        return normalizeReasonCodes(eligibleReasonCodes);
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
}
