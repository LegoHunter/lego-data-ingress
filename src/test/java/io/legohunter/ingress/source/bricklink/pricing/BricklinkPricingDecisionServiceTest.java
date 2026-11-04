package io.legohunter.ingress.source.bricklink.pricing;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingDecisionServiceTest {
    private MarketplaceListingDao marketplaceListingDao;
    private ItemInventoryDao itemInventoryDao;
    private ConditionDao conditionDao;
    private PricingSnapshotDao pricingSnapshotDao;
    private PricingSnapshotListingDao pricingSnapshotListingDao;
    private PricingDecisionDao pricingDecisionDao;
    private BricklinkPricingDecisionProperties properties;
    private BricklinkPricingDecisionService service;

    @BeforeEach
    void setUp() {
        marketplaceListingDao = mock(MarketplaceListingDao.class);
        itemInventoryDao = mock(ItemInventoryDao.class);
        conditionDao = mock(ConditionDao.class);
        pricingSnapshotDao = mock(PricingSnapshotDao.class);
        pricingSnapshotListingDao = mock(PricingSnapshotListingDao.class);
        pricingDecisionDao = mock(PricingDecisionDao.class);
        properties = new BricklinkPricingDecisionProperties();
        properties.setEnabled(true);
        properties.setBatchSize(10);

        when(pricingDecisionDao.insert(any())).thenAnswer(invocation -> {
            PricingDecision decision = invocation.getArgument(0);
            decision.setPricingDecisionId(900L);
            return decision;
        });

        service = new BricklinkPricingDecisionService(
                properties,
                marketplaceListingDao,
                itemInventoryDao,
                conditionDao,
                pricingSnapshotDao,
                pricingSnapshotListingDao,
                pricingDecisionDao,
                new ObjectMapper()
        );
    }

    @Test
    void runOnceWritesFixedPriceOverrideDecisionWithoutReadingSnapshot() {
        MarketplaceListing listing = listing(true, "250.00");
        when(marketplaceListingDao.findPricingDecisionCandidatesByListingExternalServiceIdAndListingStatusCodes(2, Set.of("ACTIVE", "DRAFT"), 10, false))
                .thenReturn(Set.of(listing));

        BricklinkPricingDecisionResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.decisionsWritten()).isOne();
        assertThat(result.skippedDecisions()).isOne();
        PricingDecision decision = capturedDecision();
        assertThat(decision.getDecisionStatusCode()).isEqualTo(BricklinkPricingDecisionService.STATUS_SKIPPED);
        assertThat(decision.getReasonCode()).isEqualTo(BricklinkPricingDecisionService.REASON_FIXED_PRICE_OVERRIDE);
        assertThat(decision.getFinalPrice()).isEqualByComparingTo("250.00");
        assertThat(decision.getComputedPrice()).isNull();
        assertThat(decision.getComparableCount()).isZero();
        verify(itemInventoryDao, never()).findByItemInventoryId(any());
        verify(pricingSnapshotDao, never()).findLatestByMarketplaceListingIdAndConditionAndCompleteness(any(), any(), any());
    }

    @Test
    void runOnceCanRequireCurrentSnapshotAtCandidateSelectionTime() {
        properties.setRequireCurrentSnapshot(true);
        when(marketplaceListingDao.findPricingDecisionCandidatesByListingExternalServiceIdAndListingStatusCodes(2, Set.of("ACTIVE", "DRAFT"), 10, true))
                .thenReturn(Set.of());

        BricklinkPricingDecisionResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_WORK");
        verify(marketplaceListingDao).findPricingDecisionCandidatesByListingExternalServiceIdAndListingStatusCodes(2, Set.of("ACTIVE", "DRAFT"), 10, true);
        verify(pricingDecisionDao, never()).insert(any());
    }

    @Test
    void runOnceUsesSingleComparableAfterOwnListingExclusion() {
        MarketplaceListing listing = listing(false, "225.00");
        arrangeListingWithSnapshot(listing, inventory("USED", "COMPLETE"), snapshot("U", "C"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(List.of(
                        comparable("BL-INV-10", "US", "200.00"),
                        comparable("BL-INV-20", "US", "220.00")
                ));

        BricklinkPricingDecisionResult result = service.runOnce();

        assertThat(result.proposedDecisions()).isOne();
        PricingDecision decision = capturedDecision();
        assertThat(decision.getDecisionStatusCode()).isEqualTo(BricklinkPricingDecisionService.STATUS_PROPOSED);
        assertThat(decision.getReasonCode()).isEqualTo(BricklinkPricingDecisionService.REASON_SINGLE_COMPARABLE_DISCOUNTED);
        assertThat(decision.getComputedPrice()).isEqualByComparingTo("213.40");
        assertThat(decision.getFinalPrice()).isEqualByComparingTo("213.40");
        assertThat(decision.getComparableCount()).isOne();
        assertThat(decision.getConfidence()).isEqualByComparingTo("0.40");
    }

    @Test
    void runOnceComputesTwoComparableWeightedPriceWithConditionAdjustment() {
        MarketplaceListing listing = listing(false, "150.00");
        ItemInventory inventory = inventory("U", "C");
        inventory.setBoxConditionId(1);
        inventory.setInstructionsConditionId(2);
        arrangeListingWithSnapshot(listing, inventory, snapshot("U", "C"));
        when(conditionDao.findConditionById(1)).thenReturn(Optional.of(condition("SL")));
        when(conditionDao.findConditionById(2)).thenReturn(Optional.of(condition("M")));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(List.of(
                        comparable("BL-INV-20", "US", "100.00"),
                        comparable("BL-INV-30", "US", "200.00")
                ));

        service.runOnce();

        PricingDecision decision = capturedDecision();
        // lower price + 25% of the gap = 125.00, times the box/instructions average (1.3 + 1.2) / 2
        assertThat(decision.getReasonCode()).isEqualTo(BricklinkPricingDecisionService.REASON_TWO_COMPARABLES_WEIGHTED);
        assertThat(decision.getComputedPrice()).isEqualByComparingTo("156.25");
        assertThat(decision.getFinalPrice()).isEqualByComparingTo("156.25");
        assertThat(decision.getComparableCount()).isEqualTo(2);
        assertThat(decision.getConfidence()).isEqualByComparingTo("0.60");
    }

    @Test
    void runOncePricesUsedCompleteAtTheBalancedTargetPercentile() {
        MarketplaceListing listing = listing(false, "130.00");
        arrangeListingWithSnapshot(listing, inventory("U", "C"), snapshot("U", "C"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(List.of(
                        comparable("BL-INV-20", "US", "100.00"),
                        comparable("BL-INV-30", "US", "120.00"),
                        comparable("BL-INV-40", "US", "140.00")
                ));

        service.runOnce();

        PricingDecision decision = capturedDecision();
        // 35th percentile of 100/120/140: position 0.7 between 100 and 120
        assertThat(decision.getReasonCode()).isEqualTo(BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        assertThat(decision.getComputedPrice()).isEqualByComparingTo("114.00");
        assertThat(decision.getFinalPrice()).isEqualByComparingTo("114.00");
        assertThat(decision.getComparableCount()).isEqualTo(3);
        assertThat(decision.getConfidence()).isEqualByComparingTo("0.75");
        assertThat(decision.getAlgorithmVersion()).isEqualTo("bricklink-market-position-v2");
        assertThat(decision.getStrategyCode()).isEqualTo("BALANCED");
        assertThat(decision.getSourceSummaryJson())
                .contains("\"targetPercentile\":0.35")
                .contains("\"aggression\":\"BALANCED\"")
                .contains("\"changeLimited\":false");
    }

    @Test
    void runOncePricesSealedAtTheSealedTargetPercentileWhateverTheSellerCountry() {
        MarketplaceListing listing = listing(false, "110.00");
        arrangeListingWithSnapshot(listing, inventory("NEW", "SEALED"), snapshot("N", "S"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(List.of(
                        comparable("BL-INV-20", "CA", "80.00"),
                        comparable("BL-INV-30", "US", "100.00"),
                        comparable("BL-INV-40", "US", "120.00")
                ));

        service.runOnce();

        PricingDecision decision = capturedDecision();
        // 20th percentile of 80/100/120: position 0.4 between 80 and 100
        assertThat(decision.getReasonCode()).isEqualTo(BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        assertThat(decision.getComputedPrice()).isEqualByComparingTo("88.00");
        assertThat(decision.getFinalPrice()).isEqualByComparingTo("88.00");
    }

    @Test
    void runOnceClampsBelowMinimumPrice() {
        properties.setMinimumPrice(new BigDecimal("100.00"));
        MarketplaceListing listing = listing(false, "110.00");
        arrangeListingWithSnapshot(listing, inventory("N", "S"), snapshot("N", "S"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(List.of(comparable("BL-INV-20", "US", "90.00")));

        service.runOnce();

        PricingDecision decision = capturedDecision();
        assertThat(decision.getReasonCode()).isEqualTo(BricklinkPricingDecisionService.REASON_BELOW_MIN_PRICE_CLAMPED);
        assertThat(decision.getComputedPrice()).isEqualByComparingTo("87.30");
        assertThat(decision.getFinalPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void runOnceClampsAboveMaximumPrice() {
        properties.setMaximumPrice(new BigDecimal("120.00"));
        MarketplaceListing listing = listing(false, "110.00");
        arrangeListingWithSnapshot(listing, inventory("U", "C"), snapshot("U", "C"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(List.of(
                        comparable("BL-INV-20", "US", "100.00"),
                        comparable("BL-INV-30", "US", "200.00")
                ));

        service.runOnce();

        PricingDecision decision = capturedDecision();
        assertThat(decision.getReasonCode()).isEqualTo(BricklinkPricingDecisionService.REASON_ABOVE_MAX_PRICE_CLAMPED);
        assertThat(decision.getComputedPrice()).isEqualByComparingTo("125.00");
        assertThat(decision.getFinalPrice()).isEqualByComparingTo("120.00");
    }

    @Test
    void runOnceIgnoresComparablesPricedFarAboveTheMedian() {
        MarketplaceListing listing = listing(false, "130.00");
        arrangeListingWithSnapshot(listing, inventory("U", "C"), snapshot("U", "C"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(List.of(
                        comparable("BL-INV-20", "US", "100.00"),
                        comparable("BL-INV-30", "US", "120.00"),
                        comparable("BL-INV-40", "US", "400.00")
                ));

        BricklinkPricingDecisionResult result = service.runOnce();

        // 400 is more than 3x the median (120), so it is dropped and the two-comparable rule applies: 100 + 25% of 20
        assertThat(result.proposedDecisions()).isOne();
        PricingDecision decision = capturedDecision();
        assertThat(decision.getDecisionStatusCode()).isEqualTo(BricklinkPricingDecisionService.STATUS_PROPOSED);
        assertThat(decision.getReasonCode()).isEqualTo(BricklinkPricingDecisionService.REASON_TWO_COMPARABLES_WEIGHTED);
        assertThat(decision.getFinalPrice()).isEqualByComparingTo("105.00");
        assertThat(decision.getComparableCount()).isEqualTo(2);
        assertThat(decision.getSourceSummaryJson()).contains("\"outliersDropped\":1");
    }

    @Test
    void runOnceKeepsEveryComparableWhenTheOutlierRuleIsOff() {
        properties.setOutlierMaxRatioToMedian(null);
        MarketplaceListing listing = listing(false, "130.00");
        arrangeListingWithSnapshot(listing, inventory("U", "C"), snapshot("U", "C"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(threeUsedComparables("100.00", "120.00", "400.00"));

        service.runOnce();

        PricingDecision decision = capturedDecision();
        assertThat(decision.getReasonCode()).isEqualTo(BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        assertThat(decision.getComputedPrice()).isEqualByComparingTo("114.00");
        assertThat(decision.getComparableCount()).isEqualTo(3);
    }

    @Test
    void runOnceUsesTheAggressionPresetForTheTargetPercentile() {
        properties.setAggression(BricklinkPricingDecisionProperties.Aggression.QUICK);
        MarketplaceListing listing = listing(false, "130.00");
        arrangeListingWithSnapshot(listing, inventory("U", "C"), snapshot("U", "C"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(threeUsedComparables("100.00", "120.00", "140.00"));

        service.runOnce();

        // QUICK targets the 20th percentile for used: position 0.4 between 100 and 120
        PricingDecision decision = capturedDecision();
        assertThat(decision.getComputedPrice()).isEqualByComparingTo("108.00");
        assertThat(decision.getStrategyCode()).isEqualTo("QUICK");
    }

    @Test
    void runOnceLetsAPerMarketTargetOverrideThePreset() {
        properties.setTargetPercentiles(Map.of("u-c", new BigDecimal("0.50"), "N-S", new BigDecimal("0.90")));
        MarketplaceListing listing = listing(false, "130.00");
        arrangeListingWithSnapshot(listing, inventory("U", "C"), snapshot("U", "C"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(threeUsedComparables("100.00", "120.00", "140.00"));

        service.runOnce();

        PricingDecision decision = capturedDecision();
        assertThat(decision.getComputedPrice()).isEqualByComparingTo("120.00");
        assertThat(decision.getSourceSummaryJson()).contains("\"targetPercentile\":0.50");
    }

    @Test
    void runOnceLimitsHowFarOneRunMovesTheCurrentPrice() {
        MarketplaceListing listing = listing(false, "200.00");
        arrangeListingWithSnapshot(listing, inventory("U", "C"), snapshot("U", "C"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(threeUsedComparables("100.00", "120.00", "140.00"));

        service.runOnce();

        // the formula says 114.00, but one run may move at most 25% from 200.00
        PricingDecision decision = capturedDecision();
        assertThat(decision.getReasonCode()).isEqualTo(BricklinkPricingDecisionService.REASON_MARKET_POSITION);
        assertThat(decision.getComputedPrice()).isEqualByComparingTo("114.00");
        assertThat(decision.getFinalPrice()).isEqualByComparingTo("150.00");
        assertThat(decision.getSourceSummaryJson()).contains("\"changeLimited\":true");
    }

    @Test
    void runOnceAlsoLimitsTheMoveByAnAmountSoReadinessDoesNotBlockIt() {
        properties.setMaximumAbsoluteChangePerRun(new BigDecimal("50.00"));
        MarketplaceListing listing = listing(false, "400.00");
        arrangeListingWithSnapshot(listing, inventory("U", "C"), snapshot("U", "C"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(threeUsedComparables("200.00", "240.00", "280.00"));

        service.runOnce();

        // the formula says 228.00; 25% of 400.00 would allow 300.00, but the $50 limit stops at 350.00
        PricingDecision decision = capturedDecision();
        assertThat(decision.getComputedPrice()).isEqualByComparingTo("228.00");
        assertThat(decision.getFinalPrice()).isEqualByComparingTo("350.00");
    }

    @Test
    void runOnceMovesStraightToTheFormulaPriceWhenTheChangeLimitIsOff() {
        properties.setMaximumChangePerRun(null);
        MarketplaceListing listing = listing(false, "200.00");
        arrangeListingWithSnapshot(listing, inventory("U", "C"), snapshot("U", "C"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(threeUsedComparables("100.00", "120.00", "140.00"));

        service.runOnce();

        assertThat(capturedDecision().getFinalPrice()).isEqualByComparingTo("114.00");
    }

    @Test
    void runOnceCanSwitchOffTheBoxAndInstructionsAdjustment() {
        properties.setBoxInstructionsAdjustmentEnabled(false);
        MarketplaceListing listing = listing(false, "150.00");
        ItemInventory inventory = inventory("U", "C");
        inventory.setBoxConditionId(1);
        inventory.setInstructionsConditionId(2);
        arrangeListingWithSnapshot(listing, inventory, snapshot("U", "C"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(List.of(
                        comparable("BL-INV-20", "US", "100.00"),
                        comparable("BL-INV-30", "US", "200.00")
                ));

        service.runOnce();

        assertThat(capturedDecision().getComputedPrice()).isEqualByComparingTo("125.00");
        verify(conditionDao, never()).findConditionById(any());
    }

    @Test
    void percentileInterpolatesBetweenTheSortedPrices() {
        List<BigDecimal> prices = List.of(new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("40"), new BigDecimal("50"));

        assertThat(BricklinkPricingDecisionService.percentile(prices, BigDecimal.ZERO)).isEqualByComparingTo("10");
        assertThat(BricklinkPricingDecisionService.percentile(prices, new BigDecimal("0.35"))).isEqualByComparingTo("24");
        assertThat(BricklinkPricingDecisionService.percentile(prices, new BigDecimal("0.5"))).isEqualByComparingTo("30");
        assertThat(BricklinkPricingDecisionService.percentile(prices, BigDecimal.ONE)).isEqualByComparingTo("50");
    }

    @Test
    void runOnceWritesFailedDecisionWhenSnapshotIsMissing() {
        MarketplaceListing listing = listing(false, "150.00");
        when(marketplaceListingDao.findPricingDecisionCandidatesByListingExternalServiceIdAndListingStatusCodes(2, Set.of("ACTIVE", "DRAFT"), 10, false))
                .thenReturn(Set.of(listing));
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("USED", "COMPLETE")));
        when(pricingSnapshotDao.findLatestByMarketplaceListingIdAndConditionAndCompleteness(10, "U", "C"))
                .thenReturn(Optional.empty());

        BricklinkPricingDecisionResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.failedDecisions()).isOne();
        PricingDecision decision = capturedDecision();
        assertThat(decision.getDecisionStatusCode()).isEqualTo(BricklinkPricingDecisionService.STATUS_FAILED);
        assertThat(decision.getReasonCode()).isEqualTo(BricklinkPricingDecisionService.REASON_NO_CURRENT_SNAPSHOT);
        assertThat(decision.getFinalPrice()).isNull();
    }

    @Test
    void runOnceWritesFailedDecisionWhenNoExactComparableRemains() {
        MarketplaceListing listing = listing(false, "150.00");
        arrangeListingWithSnapshot(listing, inventory("U", "C"), snapshot("U", "C"));
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(List.of(comparable("BL-INV-10", "US", "150.00")));

        BricklinkPricingDecisionResult result = service.runOnce();

        assertThat(result.failedDecisions()).isOne();
        PricingDecision decision = capturedDecision();
        assertThat(decision.getDecisionStatusCode()).isEqualTo(BricklinkPricingDecisionService.STATUS_FAILED);
        assertThat(decision.getReasonCode()).isEqualTo(BricklinkPricingDecisionService.REASON_NO_EXACT_COMPARABLES);
    }

    @Test
    void runOnceWritesNoCurrentComparablesWhenLatestSnapshotHasZeroRows() {
        MarketplaceListing listing = listing(false, "150.00");
        PricingSnapshot snapshot = snapshot("U", "C");
        snapshot.setComparableCount(0);
        arrangeListingWithSnapshot(listing, inventory("U", "C"), snapshot);
        when(pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(500L))
                .thenReturn(List.of());

        BricklinkPricingDecisionResult result = service.runOnce();

        assertThat(result.failedDecisions()).isOne();
        PricingDecision decision = capturedDecision();
        assertThat(decision.getDecisionStatusCode()).isEqualTo(BricklinkPricingDecisionService.STATUS_FAILED);
        assertThat(decision.getReasonCode()).isEqualTo(BricklinkPricingDecisionService.REASON_NO_CURRENT_COMPARABLES);
        assertThat(decision.getPricingSnapshotId()).isEqualTo(500L);
    }

    private List<PricingSnapshotListing> threeUsedComparables(String low, String middle, String high) {
        return List.of(
                comparable("BL-INV-20", "US", low),
                comparable("BL-INV-30", "DE", middle),
                comparable("BL-INV-40", "US", high)
        );
    }

    private void arrangeListingWithSnapshot(MarketplaceListing listing, ItemInventory inventory, PricingSnapshot snapshot) {
        when(marketplaceListingDao.findPricingDecisionCandidatesByListingExternalServiceIdAndListingStatusCodes(2, Set.of("ACTIVE", "DRAFT"), 10, false))
                .thenReturn(Set.of(listing));
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory));
        when(pricingSnapshotDao.findLatestByMarketplaceListingIdAndConditionAndCompleteness(
                listing.getMarketplaceListingId(),
                snapshot.getItemConditionCode(),
                snapshot.getCompletenessCode()
        )).thenReturn(Optional.of(snapshot));
    }

    private PricingDecision capturedDecision() {
        ArgumentCaptor<PricingDecision> decisionCaptor = ArgumentCaptor.forClass(PricingDecision.class);
        verify(pricingDecisionDao).insert(decisionCaptor.capture());
        return decisionCaptor.getValue();
    }

    private MarketplaceListing listing(boolean fixedPrice, String price) {
        return MarketplaceListing.builder()
                .marketplaceListingId(10)
                .itemInventoryId(20)
                .listingExternalServiceId(2)
                .externalCatalogItemId(30)
                .externalListingId("BL-INV-10")
                .listingStatusCode("ACTIVE")
                .unitPrice(new BigDecimal(price))
                .currencyCode("USD")
                .fixedPrice(fixedPrice)
                .build();
    }

    private ItemInventory inventory(String newOrUsed, String completeness) {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(20);
        inventory.setNewOrUsed(newOrUsed);
        inventory.setCompleteness(completeness);
        return inventory;
    }

    private PricingSnapshot snapshot(String condition, String completeness) {
        return PricingSnapshot.builder()
                .pricingSnapshotId(500L)
                .marketplaceListingId(10)
                .externalCatalogItemId(30)
                .sourceItemKey("6390-1")
                .sourceUniqueKey("4997")
                .itemConditionCode(condition)
                .completenessCode(completeness)
                .build();
    }

    private PricingSnapshotListing comparable(String externalListingId, String countryCode, String price) {
        return PricingSnapshotListing.builder()
                .pricingSnapshotId(500L)
                .externalListingId(externalListingId)
                .sellerName("seller-" + externalListingId)
                .sellerCountryCode(countryCode)
                .itemConditionCode("U")
                .completenessCode("C")
                .quantityAvailable(1)
                .unitPrice(new BigDecimal(price))
                .currencyCode("USD")
                .build();
    }

    private Condition condition(String conditionCode) {
        return Condition.builder().conditionCode(conditionCode).build();
    }
}
