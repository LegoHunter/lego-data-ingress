package io.legohunter.ingress.source.bricklink.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settings for the BrickLink pricing decision job and its market-position formula.
 * See the runbook section "Pricing formula and its levers".
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.pricing.decision")
public class BricklinkPricingDecisionProperties {
    private boolean enabled = false;
    private Integer bricklinkExternalServiceId = 2;
    private Set<String> priceableListingStatusCodes = Set.of("ACTIVE", "DRAFT");
    private String activeListingStatusCode = "ACTIVE";
    private int batchSize = 25;
    private boolean requireCurrentSnapshot = false;

    /** How hard to push for a sale. Sets the default target percentile for each market. */
    private Aggression aggression = Aggression.BALANCED;
    /**
     * Per-market target percentile (0 to 1) that overrides the preset. Keys are condition-completeness codes,
     * for example {@code U-C} (Used/Complete), {@code N-S} (Sealed), {@code N-C} (New/Complete).
     */
    private Map<String, BigDecimal> targetPercentiles = new LinkedHashMap<>();
    /** Comparables priced above this multiple of the median are ignored. Null or 0 keeps every comparable. */
    private BigDecimal outlierMaxRatioToMedian = new BigDecimal("3.0");
    /** With exactly two comparables, price at the lower one plus this share of the gap (0 to 1). */
    private BigDecimal twoComparablesFraction = new BigDecimal("0.25");
    /** Largest move from the listing's current price in one run, as a fraction. Null or 0 means no limit. */
    private BigDecimal maximumChangePerRun = new BigDecimal("0.25");
    /**
     * Largest move from the current price in one run, in listing currency. Null or 0 means no limit. Keep it at or
     * below apply-readiness {@code maximum-absolute-delta}, so big corrections move in steps instead of being blocked.
     */
    private BigDecimal maximumAbsoluteChangePerRun;
    /** Apply the box and instructions condition multipliers. */
    private boolean boxInstructionsAdjustmentEnabled = true;
    private BigDecimal minimumPrice;
    private BigDecimal maximumPrice;
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
                .collect(Collectors.toUnmodifiableSet());
        return normalizedStatusCodes.isEmpty() ? Set.of(effectiveActiveListingStatusCode()) : normalizedStatusCodes;
    }

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public Aggression effectiveAggression() {
        return aggression == null ? Aggression.BALANCED : aggression;
    }

    /** Target percentile for a market: the per-market override if set, otherwise the preset's value. */
    public BigDecimal targetPercentile(String conditionCode, String completenessCode) {
        String key = conditionCode + "-" + completenessCode;
        if (targetPercentiles != null) {
            for (Map.Entry<String, BigDecimal> entry : targetPercentiles.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && key.equalsIgnoreCase(entry.getKey().trim())) {
                    return clampFraction(entry.getValue());
                }
            }
        }
        return effectiveAggression().targetPercentile(conditionCode, completenessCode);
    }

    public BigDecimal effectiveOutlierMaxRatioToMedian() {
        return positiveOrNull(outlierMaxRatioToMedian);
    }

    public BigDecimal effectiveTwoComparablesFraction() {
        return twoComparablesFraction == null ? new BigDecimal("0.25") : clampFraction(twoComparablesFraction);
    }

    public BigDecimal effectiveMaximumChangePerRun() {
        return positiveOrNull(maximumChangePerRun);
    }

    public BigDecimal effectiveMaximumAbsoluteChangePerRun() {
        return positiveOrNull(maximumAbsoluteChangePerRun);
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }

    private static BigDecimal clampFraction(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    /**
     * Sales-aggression presets. Percentiles come from the HOMELAB-13 analysis of crawl history and BrickLink sold
     * records: lower percentiles sell faster, higher ones earn more per sale. Estimated share sold within 52 weeks:
     * QUICK about 87% used / 97% sealed, BALANCED about 76% / 89%, PATIENT about 64% / 73%.
     */
    public enum Aggression {
        /** Sell within about six months. */
        QUICK("0.20", "0.10", "0.10"),
        /** Sell within about twelve months at the best price per sale. */
        BALANCED("0.35", "0.20", "0.20"),
        /** Hold out for price; eighteen to twenty-four months is acceptable. */
        PATIENT("0.50", "0.30", "0.30");

        private final BigDecimal used;
        private final BigDecimal sealed;
        private final BigDecimal otherNew;

        Aggression(String used, String sealed, String otherNew) {
            this.used = new BigDecimal(used);
            this.sealed = new BigDecimal(sealed);
            this.otherNew = new BigDecimal(otherNew);
        }

        BigDecimal targetPercentile(String conditionCode, String completenessCode) {
            if ("N".equals(conditionCode)) {
                return "S".equals(completenessCode) ? sealed : otherNew;
            }
            return used;
        }
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
