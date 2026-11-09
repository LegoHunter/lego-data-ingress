package io.legohunter.ingress.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
     * for example {@code U-C} (Used/Complete), {@code N-S} (Sealed), {@code N-C} (New/Complete). Used markets can
     * add the listing's own packaging: {@code U-C-BI} (box and instructions), {@code U-C-ONE} (one of the two),
     * {@code U-C-NONE} (neither). The most specific key wins.
     */
    private Map<String, BigDecimal> targetPercentiles = new LinkedHashMap<>();
    /**
     * BrickLink seller usernames of your own store(s). Their lots are never counted as competitors, so a listing is
     * never priced against your own other lots. Matched case-insensitively against the comparable's seller name.
     */
    private Set<String> ownSellerNames = new LinkedHashSet<>();
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
    /** Apply the small box and instructions condition-grade adjustments to used items. */
    private boolean boxInstructionsAdjustmentEnabled = true;
    /** Lower the price of an active listing a little each week it stays unsold. Off by default. */
    private StepDown stepDown = new StepDown();
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

    /**
     * Target percentile for a market and the listing's own packaging (null for new items): an override for the market
     * and packaging, then an override for the market, then the preset's value.
     */
    public BigDecimal targetPercentile(String conditionCode, String completenessCode, Packaging packaging) {
        String market = conditionCode + "-" + completenessCode;
        if (packaging != null) {
            BigDecimal specific = override(market + "-" + packaging.name());
            if (specific != null) {
                return specific;
            }
        }
        BigDecimal general = override(market);
        return general != null ? general : effectiveAggression().targetPercentile(conditionCode, completenessCode, packaging);
    }

    private BigDecimal override(String key) {
        if (targetPercentiles == null) {
            return null;
        }
        for (Map.Entry<String, BigDecimal> entry : targetPercentiles.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && key.equalsIgnoreCase(entry.getKey().trim())) {
                return clampFraction(entry.getValue());
            }
        }
        return null;
    }

    public boolean isOwnSeller(String sellerName) {
        if (sellerName == null || sellerName.isBlank() || ownSellerNames == null) {
            return false;
        }
        String name = sellerName.trim();
        return ownSellerNames.stream().anyMatch(own -> own != null && own.trim().equalsIgnoreCase(name));
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

    public BigDecimal effectiveStepDownWeeklyPercent() {
        return stepDown == null ? null : positiveOrNull(stepDown.weeklyPercent);
    }

    public BigDecimal effectiveStepDownMaximumTotalPercent() {
        return stepDown == null || stepDown.maximumTotalPercent == null ? null : clampFraction(stepDown.maximumTotalPercent);
    }

    public BigDecimal effectiveStepDownFloorPercentile() {
        return stepDown == null || stepDown.floorPercentile == null ? null : clampFraction(stepDown.floorPercentile);
    }

    public boolean isStepDownEnabled() {
        return stepDown != null && stepDown.enabled;
    }

    public int effectiveStepDownStartAfterWeeks() {
        return stepDown == null ? 0 : Math.max(0, stepDown.startAfterWeeks);
    }

    /**
     * What a used item comes with, from its own inventory record: box and instructions, one of the two, or neither.
     * Competitors' packaging is not structured data on BrickLink, so it is not read; instead a used item with its box
     * and instructions targets a higher percentile of the whole market (where such lots sit).
     */
    public enum Packaging {
        BI, ONE, NONE;

        static Packaging of(boolean hasBox, boolean hasInstructions) {
            if (hasBox && hasInstructions) {
                return BI;
            }
            return hasBox || hasInstructions ? ONE : NONE;
        }
    }

    /**
     * Sales-aggression presets. Percentiles come from the HOMELAB-13 analysis of crawl history and BrickLink sold
     * records: lower percentiles sell faster, higher ones earn more per sale. For used items, the target depends on
     * the item's packaging. Each used value is the whole-market percentile where the preset's position among similar
     * sellers lands: lots that say they include a box and instructions sit at about the 73rd percentile of the whole
     * market, lots with neither at about the 31st.
     */
    public enum Aggression {
        /** Sell within about six months. */
        QUICK("0.55", "0.20", "0.15", "0.10", "0.10"),
        /** Sell within about twelve months at the best price per sale. */
        BALANCED("0.65", "0.35", "0.25", "0.20", "0.20"),
        /** Hold out for price; eighteen to twenty-four months is acceptable. */
        PATIENT("0.75", "0.45", "0.35", "0.30", "0.30");

        private final BigDecimal usedBoxAndInstructions;
        private final BigDecimal usedOneOfTheTwo;
        private final BigDecimal usedNeither;
        private final BigDecimal sealed;
        private final BigDecimal otherNew;

        Aggression(String usedBoxAndInstructions, String usedOneOfTheTwo, String usedNeither, String sealed, String otherNew) {
            this.usedBoxAndInstructions = new BigDecimal(usedBoxAndInstructions);
            this.usedOneOfTheTwo = new BigDecimal(usedOneOfTheTwo);
            this.usedNeither = new BigDecimal(usedNeither);
            this.sealed = new BigDecimal(sealed);
            this.otherNew = new BigDecimal(otherNew);
        }

        BigDecimal targetPercentile(String conditionCode, String completenessCode, Packaging packaging) {
            if ("N".equals(conditionCode)) {
                return "S".equals(completenessCode) ? sealed : otherNew;
            }
            if (packaging == null) {
                return usedOneOfTheTwo;
            }
            return switch (packaging) {
                case BI -> usedBoxAndInstructions;
                case ONE -> usedOneOfTheTwo;
                case NONE -> usedNeither;
            };
        }
    }

    /**
     * Weekly price step-down for active listings that have not sold. The discount depends on the weeks since the
     * listing was first published, not on how many times the job runs, so frequent runs never compound it.
     */
    @Getter
    @Setter
    public static class StepDown {
        private boolean enabled = false;
        /** Full market price for this many weeks after publishing. */
        private int startAfterWeeks = 8;
        /** Price falls by this fraction for each further week unsold (compounding). */
        private BigDecimal weeklyPercent = new BigDecimal("0.01");
        /** Never more than this fraction below the market price. */
        private BigDecimal maximumTotalPercent = new BigDecimal("0.20");
        /** Never below this percentile of the current competing lots (3 or more comparables). */
        private BigDecimal floorPercentile = new BigDecimal("0.10");
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
