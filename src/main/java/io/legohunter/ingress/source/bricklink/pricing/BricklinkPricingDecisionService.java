package io.legohunter.ingress.source.bricklink.pricing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.data.dao.ConditionDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dao.PricingSnapshotDao;
import io.legohunter.data.dao.PricingSnapshotListingDao;
import io.legohunter.data.dto.Condition;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.PricingDecision;
import io.legohunter.data.dto.PricingSnapshot;
import io.legohunter.data.dto.PricingSnapshotListing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.pricing.decision", name = "enabled", havingValue = "true")
public class BricklinkPricingDecisionService {
    static final String STATUS_PROPOSED = "PROPOSED";
    static final String STATUS_SKIPPED = "SKIPPED";
    static final String STATUS_FAILED = "FAILED";

    static final String ALGORITHM_VERSION = "bricklink-market-position-v2";

    static final String REASON_FIXED_PRICE_OVERRIDE = "FIXED_PRICE_OVERRIDE";
    static final String REASON_MARKET_POSITION = "MARKET_POSITION";
    static final String REASON_SINGLE_COMPARABLE_DISCOUNTED = "SINGLE_COMPARABLE_DISCOUNTED";
    static final String REASON_TWO_COMPARABLES_WEIGHTED = "TWO_COMPARABLES_WEIGHTED";
    static final String REASON_NO_CURRENT_SNAPSHOT = "NO_CURRENT_SNAPSHOT";
    static final String REASON_NO_CURRENT_COMPARABLES = "NO_CURRENT_COMPARABLES";
    static final String REASON_NO_EXACT_COMPARABLES = "NO_EXACT_COMPARABLES";
    static final String REASON_MISSING_INVENTORY = "MISSING_INVENTORY";
    static final String REASON_MISSING_CONDITION = "MISSING_CONDITION";
    static final String REASON_MISSING_COMPLETENESS = "MISSING_COMPLETENESS";
    static final String REASON_BELOW_MIN_PRICE_CLAMPED = "BELOW_MIN_PRICE_CLAMPED";
    static final String REASON_ABOVE_MAX_PRICE_CLAMPED = "ABOVE_MAX_PRICE_CLAMPED";

    /** Box or instructions condition codes that mean the item does not have it. */
    private static final Set<String> ABSENT_CODES = Set.of("MS", "NA");
    /**
     * Small condition-grade adjustments for a used item's box and instructions, applied on top of the packaging's
     * target percentile. Having the box or instructions is rewarded by the higher target; a grade moves the price by
     * at most -15% / +10%, far less than that premium. Missing or unknown items count as 1.0.
     */
    private static final Map<String, BigDecimal> GRADE_ADJUSTMENTS = Map.of(
            "SL", bd("1.10"),
            "M", bd("1.05"),
            "E", bd("1.00"),
            "VG", bd("0.98"),
            "G", bd("0.95"),
            "F", bd("0.90"),
            "P", bd("0.85"),
            "CC", bd("0.90"),
            "BW", bd("0.85")
    );

    private final BricklinkPricingDecisionProperties properties;
    private final MarketplaceListingDao marketplaceListingDao;
    private final ItemInventoryDao itemInventoryDao;
    private final ConditionDao conditionDao;
    private final PricingSnapshotDao pricingSnapshotDao;
    private final PricingSnapshotListingDao pricingSnapshotListingDao;
    private final PricingDecisionDao pricingDecisionDao;
    private final ObjectMapper objectMapper;

    public BricklinkPricingDecisionResult runOnce() {
        long start = System.currentTimeMillis();
        Set<MarketplaceListing> listings = marketplaceListingDao.findPricingDecisionCandidatesByListingExternalServiceIdAndListingStatusCodes(
                properties.getBricklinkExternalServiceId(),
                properties.effectivePriceableListingStatusCodes(),
                properties.effectiveBatchSize(),
                properties.isRequireCurrentSnapshot()
        );

        if (listings.isEmpty()) {
            return BricklinkPricingDecisionResult.noWork(elapsedMillis(start));
        }

        DecisionCounters counters = new DecisionCounters(listings.size());
        for (MarketplaceListing listing : listings) {
            PricingDecision decision = processListing(listing);
            pricingDecisionDao.insert(decision);
            counters.record(decision);
        }

        String outcome = counters.failedDecisions > 0 ? "PARTIAL_SUCCESS" : "SUCCESS";
        return new BricklinkPricingDecisionResult(
                outcome,
                counters.listingsSelected,
                counters.decisionsWritten,
                counters.proposedDecisions,
                counters.skippedDecisions,
                counters.failedDecisions,
                elapsedMillis(start)
        );
    }

    private PricingDecision processListing(MarketplaceListing listing) {
        if (Boolean.TRUE.equals(listing.getFixedPrice())) {
            return decision(
                    listing,
                    null,
                    STATUS_SKIPPED,
                    REASON_FIXED_PRICE_OVERRIDE,
                    null,
                    money(listing.getUnitPrice()),
                    0,
                    bd("1.00"),
                    summary(Map.of("fixedPrice", true)),
                    "Marketplace listing is marked fixed price; preserving current listing price."
            );
        }

        Optional<ItemInventory> inventory = itemInventoryDao.findByItemInventoryId(listing.getItemInventoryId());
        if (inventory.isEmpty()) {
            return failed(listing, null, REASON_MISSING_INVENTORY, "No item_inventory row found for marketplace listing.");
        }

        String conditionCode = BricklinkPricingCodeNormalizer.condition(inventory.get().getNewOrUsed());
        if (conditionCode == null) {
            return failed(listing, null, REASON_MISSING_CONDITION, "Missing or unsupported item_inventory.new_or_used.");
        }

        String completenessCode = BricklinkPricingCodeNormalizer.completeness(inventory.get().getCompleteness());
        if (completenessCode == null) {
            return failed(listing, null, REASON_MISSING_COMPLETENESS, "Missing item_inventory.completeness.");
        }

        Optional<PricingSnapshot> snapshot = pricingSnapshotDao.findLatestByMarketplaceListingIdAndConditionAndCompleteness(
                listing.getMarketplaceListingId(),
                conditionCode,
                completenessCode
        );
        if (snapshot.isEmpty()) {
            return failed(listing, null, REASON_NO_CURRENT_SNAPSHOT, "No pricing snapshot exists for listing condition and completeness.");
        }

        List<PricingSnapshotListing> candidates = pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(snapshot.get().getPricingSnapshotId())
                .stream()
                .filter(comparable -> comparable.getUnitPrice() != null)
                .filter(comparable -> !isOwnListing(listing, comparable))
                .sorted(Comparator.comparing(PricingSnapshotListing::getUnitPrice))
                .toList();
        List<PricingSnapshotListing> exactComparables = withoutOutliers(candidates);
        int outliersDropped = candidates.size() - exactComparables.size();

        if (exactComparables.isEmpty()) {
            if (Integer.valueOf(0).equals(snapshot.get().getComparableCount())) {
                return failed(
                        listing,
                        snapshot.get(),
                        REASON_NO_CURRENT_COMPARABLES,
                        "Current pricing snapshot contains no BrickLink comparable listing rows."
                );
            }
            return failed(
                    listing,
                    snapshot.get(),
                    REASON_NO_EXACT_COMPARABLES,
                    "No exact comparable rows remain after condition/completeness filtering and own-listing exclusion."
            );
        }

        ItemPackaging item = itemPackaging(conditionCode, inventory.get());
        List<BigDecimal> prices = exactComparables.stream().map(PricingSnapshotListing::getUnitPrice).toList();
        Calculation calculation = calculate(conditionCode, completenessCode, item, prices);
        StepDown stepDown = stepDown(listing, calculation.price(), prices);
        BigDecimal limitedPrice = limitChange(stepDown.price(), listing.getUnitPrice());
        PriceClamp clamp = clamp(limitedPrice);
        String reasonCode = clamp.reasonCode().orElse(calculation.reasonCode());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("packaging", item.packaging() == null ? "" : item.packaging().name());
        details.put("targetPercentile", nullToEmpty(calculation.targetPercentile()));
        details.put("outliersDropped", outliersDropped);
        details.put("marketPrice", money(calculation.price()));
        details.put("weeksUnsold", nullToEmpty(stepDown.weeksUnsold()));
        details.put("stepDownPercent", stepDown.percent());
        details.put("changeLimited", limitedPrice.compareTo(stepDown.price()) != 0);
        return decision(
                listing,
                snapshot.get(),
                STATUS_PROPOSED,
                reasonCode,
                stepDown.price(),
                clamp.price(),
                exactComparables.size(),
                confidence(exactComparables.size()),
                sourceSummary(snapshot.get(), exactComparables, calculation.reasonCode(), reasonCode, details),
                null
        );
    }

    private PricingDecision failed(MarketplaceListing listing, PricingSnapshot snapshot, String reasonCode, String notes) {
        return decision(
                listing,
                snapshot,
                STATUS_FAILED,
                reasonCode,
                null,
                null,
                0,
                bd("0.00"),
                snapshot == null ? null : sourceSummary(snapshot, List.of(), reasonCode, reasonCode, Map.of()),
                notes
        );
    }

    private PricingDecision decision(
            MarketplaceListing listing,
            PricingSnapshot snapshot,
            String statusCode,
            String reasonCode,
            BigDecimal computedPrice,
            BigDecimal finalPrice,
            int comparableCount,
            BigDecimal confidence,
            String sourceSummaryJson,
            String notes
    ) {
        return PricingDecision.builder()
                .marketplaceListingId(listing.getMarketplaceListingId())
                .pricingSnapshotId(snapshot == null ? null : snapshot.getPricingSnapshotId())
                .algorithmVersion(ALGORITHM_VERSION)
                .decisionStatusCode(statusCode)
                .reasonCode(reasonCode)
                .strategyCode(properties.effectiveAggression().name())
                .computedPrice(money(computedPrice))
                .finalPrice(money(finalPrice))
                .previousPrice(money(listing.getUnitPrice()))
                .currencyCode(currencyCode(listing))
                .comparableCount(comparableCount)
                .confidence(confidence)
                .sourceSummaryJson(sourceSummaryJson)
                .notes(notes)
                .appliedAt(null)
                .createdAt(now())
                .build();
    }

    /**
     * Market-position formula. Comparables are this week's lots with the same condition and sub-condition,
     * sorted by price. Three or more: the target percentile of their prices, which for used items depends on the
     * item's own box and instructions. Two: the lower price plus a share of the gap. One: just under it.
     */
    private Calculation calculate(String conditionCode, String completenessCode, ItemPackaging item, List<BigDecimal> prices) {
        if (prices.size() > 2) {
            BigDecimal target = properties.targetPercentile(conditionCode, completenessCode, item.packaging());
            return new Calculation(adjustPrice(item, percentile(prices, target)), REASON_MARKET_POSITION, target);
        }
        if (prices.size() == 2) {
            BigDecimal low = prices.get(0);
            BigDecimal base = low.add(prices.get(1).subtract(low).multiply(properties.effectiveTwoComparablesFraction()));
            return new Calculation(adjustPrice(item, base), REASON_TWO_COMPARABLES_WEIGHTED, null);
        }
        return new Calculation(adjustPrice(item, undercut(prices.getFirst())), REASON_SINGLE_COMPARABLE_DISCOUNTED, null);
    }

    /**
     * Weekly step-down for an active listing that has not sold: after {@code start-after-weeks} from publishing, the
     * price falls by {@code weekly-percent} per further week, compounding, but never more than
     * {@code maximum-total-percent} below the market price and never below the {@code floor-percentile} of the
     * current competing lots. It is computed from the listing's age, so repeated runs never compound it.
     */
    private StepDown stepDown(MarketplaceListing listing, BigDecimal marketPrice, List<BigDecimal> prices) {
        Long weeksUnsold = weeksUnsold(listing);
        BigDecimal weekly = properties.effectiveStepDownWeeklyPercent();
        if (!properties.isStepDownEnabled() || weeksUnsold == null || weekly == null) {
            return new StepDown(marketPrice, weeksUnsold, BigDecimal.ZERO);
        }
        long steps = weeksUnsold - properties.effectiveStepDownStartAfterWeeks();
        if (steps <= 0) {
            return new StepDown(marketPrice, weeksUnsold, BigDecimal.ZERO);
        }
        BigDecimal factor = BigDecimal.ONE.subtract(weekly).max(BigDecimal.ZERO).pow((int) Math.min(steps, 520));
        BigDecimal price = marketPrice.multiply(factor);
        BigDecimal maximumTotal = properties.effectiveStepDownMaximumTotalPercent();
        if (maximumTotal != null) {
            price = price.max(marketPrice.multiply(BigDecimal.ONE.subtract(maximumTotal)));
        }
        BigDecimal floorPercentile = properties.effectiveStepDownFloorPercentile();
        if (floorPercentile != null && prices.size() > 2) {
            price = price.max(percentile(prices, floorPercentile).min(marketPrice));
        }
        BigDecimal percent = marketPrice.signum() > 0
                ? BigDecimal.ONE.subtract(price.divide(marketPrice, 4, RoundingMode.HALF_UP))
                : BigDecimal.ZERO;
        return new StepDown(price, weeksUnsold, percent);
    }

    /** Whole weeks since an active listing was first published; null for drafts and unpublished listings. */
    private Long weeksUnsold(MarketplaceListing listing) {
        String status = clean(listing.getListingStatusCode());
        if (listing.getPublishedAt() == null || status == null
                || !properties.effectiveActiveListingStatusCode().equalsIgnoreCase(status)) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(listing.getPublishedAt(), now());
        return Math.max(0, days / 7);
    }

    /** Linear interpolation between the two sorted prices around position {@code fraction * (n - 1)}. */
    static BigDecimal percentile(List<BigDecimal> sortedPrices, BigDecimal fraction) {
        BigDecimal position = fraction.multiply(BigDecimal.valueOf(sortedPrices.size() - 1L));
        int lower = position.intValue();
        int upper = Math.min(lower + 1, sortedPrices.size() - 1);
        BigDecimal weight = position.subtract(BigDecimal.valueOf(lower));
        BigDecimal low = sortedPrices.get(lower);
        return low.add(sortedPrices.get(upper).subtract(low).multiply(weight));
    }

    private BigDecimal undercut(BigDecimal price) {
        BigDecimal priceMinusThreePercentUpToTenDollars = price.subtract(price.multiply(bd("0.03")).min(bd("10")));
        BigDecimal priceMinusOneDollar = price.subtract(BigDecimal.ONE).max(BigDecimal.ONE);
        return priceMinusThreePercentUpToTenDollars.min(priceMinusOneDollar);
    }

    /** Drops lots priced above the configured multiple of the median (absurd asks would otherwise skew the market). */
    private List<PricingSnapshotListing> withoutOutliers(List<PricingSnapshotListing> sortedComparables) {
        BigDecimal maxRatio = properties.effectiveOutlierMaxRatioToMedian();
        if (maxRatio == null || sortedComparables.size() < 3) {
            return sortedComparables;
        }
        List<BigDecimal> prices = sortedComparables.stream().map(PricingSnapshotListing::getUnitPrice).toList();
        BigDecimal limit = percentile(prices, bd("0.5")).multiply(maxRatio);
        return sortedComparables.stream().filter(comparable -> comparable.getUnitPrice().compareTo(limit) <= 0).toList();
    }

    /**
     * Keeps the move from the current price within the configured fraction and amount, so large corrections happen
     * over several runs instead of being blocked by apply readiness.
     */
    private BigDecimal limitChange(BigDecimal price, BigDecimal currentPrice) {
        if (currentPrice == null || currentPrice.signum() <= 0) {
            return price;
        }
        BigDecimal maxStep = null;
        BigDecimal fraction = properties.effectiveMaximumChangePerRun();
        if (fraction != null) {
            maxStep = currentPrice.multiply(fraction);
        }
        BigDecimal amount = properties.effectiveMaximumAbsoluteChangePerRun();
        if (amount != null) {
            maxStep = maxStep == null ? amount : maxStep.min(amount);
        }
        if (maxStep == null) {
            return price;
        }
        return price.max(currentPrice.subtract(maxStep)).min(currentPrice.add(maxStep));
    }

    private BigDecimal adjustPrice(ItemPackaging item, BigDecimal price) {
        if (!properties.isBoxInstructionsAdjustmentEnabled() || item.packaging() == null) {
            return price;
        }
        BigDecimal box = item.boxCode().map(code -> GRADE_ADJUSTMENTS.getOrDefault(code, BigDecimal.ONE)).orElse(BigDecimal.ONE);
        BigDecimal instructions = item.instructionsCode().map(code -> GRADE_ADJUSTMENTS.getOrDefault(code, BigDecimal.ONE)).orElse(BigDecimal.ONE);
        return price.multiply(box.add(instructions).divide(bd("2.0"), 8, RoundingMode.HALF_UP));
    }

    /**
     * What a used item comes with, from its own inventory: box and instructions, one of the two, or neither.
     * Codes MS (missing) and NA (not applicable) count as not having it. New items get no packaging: sealed lots all
     * have their box, and New/Complete has too little data to calibrate.
     */
    private ItemPackaging itemPackaging(String conditionCode, ItemInventory inventory) {
        if (!"U".equals(conditionCode)) {
            return new ItemPackaging(Optional.empty(), Optional.empty(), null);
        }
        Optional<String> box = conditionCode(inventory.getBoxConditionId()).filter(code -> !ABSENT_CODES.contains(code));
        Optional<String> instructions = conditionCode(inventory.getInstructionsConditionId()).filter(code -> !ABSENT_CODES.contains(code));
        return new ItemPackaging(box, instructions, BricklinkPricingDecisionProperties.Packaging.of(box.isPresent(), instructions.isPresent()));
    }

    private Optional<String> conditionCode(Integer conditionId) {
        if (conditionId == null) {
            return Optional.empty();
        }
        return conditionDao.findConditionById(conditionId)
                .map(Condition::getConditionCode)
                .map(String::trim)
                .map(String::toUpperCase);
    }

    private PriceClamp clamp(BigDecimal computedPrice) {
        BigDecimal price = money(computedPrice);
        BigDecimal minimumPrice = properties.getMinimumPrice();
        if (minimumPrice != null && price.compareTo(minimumPrice) < 0) {
            return new PriceClamp(money(minimumPrice), Optional.of(REASON_BELOW_MIN_PRICE_CLAMPED));
        }
        BigDecimal maximumPrice = properties.getMaximumPrice();
        if (maximumPrice != null && price.compareTo(maximumPrice) > 0) {
            return new PriceClamp(money(maximumPrice), Optional.of(REASON_ABOVE_MAX_PRICE_CLAMPED));
        }
        return new PriceClamp(price, Optional.empty());
    }

    private BigDecimal confidence(int comparableCount) {
        if (comparableCount >= 5) {
            return bd("0.90");
        }
        if (comparableCount >= 3) {
            return bd("0.75");
        }
        if (comparableCount == 2) {
            return bd("0.60");
        }
        if (comparableCount == 1) {
            return bd("0.40");
        }
        return bd("0.00");
    }

    private String sourceSummary(
            PricingSnapshot snapshot,
            List<PricingSnapshotListing> comparables,
            String baseReasonCode,
            String finalReasonCode,
            Map<String, Object> details
    ) {
        BigDecimal lowestPrice = comparables.isEmpty() ? null : money(comparables.getFirst().getUnitPrice());
        BigDecimal highestPrice = comparables.isEmpty() ? null : money(comparables.getLast().getUnitPrice());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("pricingSnapshotId", snapshot.getPricingSnapshotId());
        summary.put("sourceItemKey", nullToEmpty(snapshot.getSourceItemKey()));
        summary.put("sourceUniqueKey", nullToEmpty(snapshot.getSourceUniqueKey()));
        summary.put("targetConditionCode", nullToEmpty(snapshot.getItemConditionCode()));
        summary.put("targetCompletenessCode", nullToEmpty(snapshot.getCompletenessCode()));
        summary.put("exactComparableCount", comparables.size());
        summary.put("lowestComparablePrice", nullToEmpty(lowestPrice));
        summary.put("highestComparablePrice", nullToEmpty(highestPrice));
        summary.put("aggression", properties.effectiveAggression().name());
        summary.put("baseReasonCode", baseReasonCode);
        summary.put("finalReasonCode", finalReasonCode);
        summary.putAll(details);
        return summary(summary);
    }

    private String summary(Map<String, ?> summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize pricing decision source summary", e);
        }
    }

    private String currencyCode(MarketplaceListing listing) {
        return clean(listing.getCurrencyCode()) == null ? "USD" : listing.getCurrencyCode().trim().toUpperCase();
    }

    private boolean isOwnListing(MarketplaceListing listing, PricingSnapshotListing comparable) {
        String listingExternalId = clean(listing.getExternalListingId());
        return listingExternalId != null && Objects.equals(listingExternalId, clean(comparable.getExternalListingId()));
    }

    private Object nullToEmpty(Object value) {
        return value == null ? "" : value;
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    private long elapsedMillis(long start) {
        return System.currentTimeMillis() - start;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record Calculation(BigDecimal price, String reasonCode, BigDecimal targetPercentile) {
    }

    private record StepDown(BigDecimal price, Long weeksUnsold, BigDecimal percent) {
    }

    private record ItemPackaging(
            Optional<String> boxCode,
            Optional<String> instructionsCode,
            BricklinkPricingDecisionProperties.Packaging packaging
    ) {
    }

    private record PriceClamp(BigDecimal price, Optional<String> reasonCode) {
    }

    private static final class DecisionCounters {
        private final int listingsSelected;
        private int decisionsWritten;
        private int proposedDecisions;
        private int skippedDecisions;
        private int failedDecisions;

        private DecisionCounters(int listingsSelected) {
            this.listingsSelected = listingsSelected;
        }

        private void record(PricingDecision decision) {
            decisionsWritten++;
            if (STATUS_PROPOSED.equals(decision.getDecisionStatusCode())) {
                proposedDecisions++;
            } else if (STATUS_SKIPPED.equals(decision.getDecisionStatusCode())) {
                skippedDecisions++;
            } else if (STATUS_FAILED.equals(decision.getDecisionStatusCode())) {
                failedDecisions++;
            }
        }
    }
}
