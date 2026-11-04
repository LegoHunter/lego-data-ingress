package io.legohunter.ingress.source.bricklink.pricing;

import com.bricklink.api.ajax.BricklinkAjaxClient;
import com.bricklink.api.ajax.exception.BricklinkAjaxClientException;
import com.bricklink.api.ajax.model.v1.Item;
import com.bricklink.api.ajax.model.v1.ItemForSale;
import com.bricklink.api.ajax.support.CatalogItemsForSaleResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.PricingCrawlWorkItemDao;
import io.legohunter.data.dao.PricingSnapshotDao;
import io.legohunter.data.dao.PricingSnapshotListingDao;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.PricingCrawlWorkItem;
import io.legohunter.data.dto.PricingSnapshot;
import io.legohunter.data.dto.PricingSnapshotListing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.pricing.crawl", name = "enabled", havingValue = "true")
public class BricklinkPricingCrawlService {
    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_CLAIMED = "CLAIMED";
    static final String STATUS_SUCCEEDED = "SUCCEEDED";
    static final String STATUS_SKIPPED_MISSING_ITEM_NUMBER = "SKIPPED_MISSING_ITEM_NUMBER";
    static final String STATUS_SKIPPED_MISSING_CONDITION = "SKIPPED_MISSING_CONDITION";
    static final String STATUS_SKIPPED_MISSING_LISTING = "SKIPPED_MISSING_LISTING";
    static final String STATUS_SKIPPED_MISSING_CATALOG = "SKIPPED_MISSING_CATALOG";
    static final String STATUS_FAILED_ITEM_ID_LOOKUP_NO_MATCH = "FAILED_ITEM_ID_LOOKUP_NO_MATCH";
    static final String STATUS_FAILED_ITEM_ID_LOOKUP_AMBIGUOUS = "FAILED_ITEM_ID_LOOKUP_AMBIGUOUS";
    static final String STATUS_FAILED_ITEM_ID_LOOKUP_HTTP_ERROR = "FAILED_ITEM_ID_LOOKUP_HTTP_ERROR";
    static final String STATUS_FAILED_PRICING_HTTP_ERROR = "FAILED_PRICING_HTTP_ERROR";
    static final String STATUS_FAILED_PRICING_PARSE_ERROR = "FAILED_PRICING_PARSE_ERROR";

    private static final String CATALOG_ITEMS_FOR_SALE_PATH = "/ajax/clone/catalogifs.ajax";

    private final BricklinkAjaxClient bricklinkAjaxClient;
    private final BricklinkPricingCrawlProperties properties;
    private final MarketplaceListingDao marketplaceListingDao;
    private final ExternalCatalogItemDao externalCatalogItemDao;
    private final ItemInventoryDao itemInventoryDao;
    private final PricingCrawlWorkItemDao pricingCrawlWorkItemDao;
    private final PricingSnapshotDao pricingSnapshotDao;
    private final PricingSnapshotListingDao pricingSnapshotListingDao;
    private final ObjectMapper objectMapper;

    public BricklinkPricingCrawlResult runOnce() {
        long start = System.currentTimeMillis();
        ZonedDateTime runAt = now();
        CrawlCounters counters = new CrawlCounters();
        counters.staleWorkItemsRequeued = pricingCrawlWorkItemDao.requeueStaleClaimed(
                STATUS_CLAIMED,
                STATUS_PENDING,
                runAt.minus(properties.effectiveClaimStaleAfter()),
                adjustForBlackout(runAt.plus(properties.effectiveRetryBackoff())),
                "Recovered stale pricing crawl claim"
        );

        scheduleWork(runAt, counters);

        Set<PricingCrawlWorkItem> claimedWorkItems = pricingCrawlWorkItemDao.claimDueWorkItems(
                STATUS_PENDING,
                STATUS_CLAIMED,
                runAt,
                runAt,
                properties.effectiveWorkerBatchSize()
        );
        counters.workItemsClaimed = claimedWorkItems.size();

        for (PricingCrawlWorkItem workItem : claimedWorkItems) {
            processWorkItem(workItem, counters);
        }

        String outcome = outcome(counters);
        if ("NO_WORK".equals(outcome)) {
            return BricklinkPricingCrawlResult.noWork(elapsedMillis(start));
        }
        return new BricklinkPricingCrawlResult(
                outcome,
                counters.listingsSelected,
                counters.workItemsScheduled,
                counters.workItemsClaimed,
                counters.staleWorkItemsRequeued,
                counters.snapshotsWritten,
                counters.zeroComparableSnapshotsWritten,
                counters.snapshotListingsWritten,
                counters.hydratedCatalogItems,
                counters.catalogItemLookupNoMatches,
                counters.catalogItemLookupAmbiguousMatches,
                counters.catalogItemLookupFailures,
                counters.skippedListings,
                counters.failedListings,
                elapsedMillis(start)
        );
    }

    private void scheduleWork(ZonedDateTime runAt, CrawlCounters counters) {
        Set<MarketplaceListing> candidates = marketplaceListingDao.findPricingCrawlSchedulingCandidatesByListingExternalServiceIdAndListingStatusCodes(
                properties.getBricklinkExternalServiceId(),
                properties.effectivePriceableListingStatusCodes(),
                STATUS_PENDING,
                STATUS_CLAIMED,
                runAt,
                properties.effectiveBatchSize()
        );

        List<MarketplaceListing> listings = candidates.stream()
                .filter(this::isAllowedListing)
                .sorted(Comparator.comparing(MarketplaceListing::getMarketplaceListingId))
                .toList();
        counters.listingsSelected = listings.size();
        for (int index = 0; index < listings.size(); index++) {
            MarketplaceListing listing = listings.get(index);
            ExternalCatalogItem catalogItem = listing.getExternalCatalogItem();
            if (catalogItem == null) {
                counters.skippedListings++;
                log.warn("bricklink.pricing.crawl.schedule.skipped_missing_catalog marketplaceListingId={}", listing.getMarketplaceListingId());
                continue;
            }
            pricingCrawlWorkItemDao.insert(PricingCrawlWorkItem.builder()
                    .marketplaceListingId(listing.getMarketplaceListingId())
                    .externalCatalogItemId(catalogItem.getExternalCatalogItemId())
                    .sourceExternalServiceId(properties.getBricklinkExternalServiceId())
                    .workStatusCode(STATUS_PENDING)
                    .attemptCount(0)
                    .maxAttempts(properties.effectiveMaxAttempts())
                    .nextAttemptAt(scheduledAttemptAt(runAt, index, listings.size()))
                    .build());
            counters.workItemsScheduled++;
        }
    }

    private boolean isAllowedListing(MarketplaceListing listing) {
        Set<Integer> allowlist = properties.effectiveMarketplaceListingAllowlist();
        return allowlist.isEmpty() || allowlist.contains(listing.getMarketplaceListingId());
    }

    private void processWorkItem(PricingCrawlWorkItem workItem, CrawlCounters counters) {
        Optional<MarketplaceListing> foundListing = marketplaceListingDao.findByMarketplaceListingId(workItem.getMarketplaceListingId());
        if (foundListing.isEmpty()) {
            completeWorkItem(workItem, STATUS_SKIPPED_MISSING_LISTING, "Missing marketplace_listing row");
            counters.skippedListings++;
            return;
        }

        MarketplaceListing listing = foundListing.get();
        ExternalCatalogItem catalogItem = listing.getExternalCatalogItem();
        if (catalogItem == null) {
            completeWorkItem(workItem, STATUS_SKIPPED_MISSING_CATALOG, "Missing external_catalog_item row");
            counters.skippedListings++;
            log.warn("bricklink.pricing.crawl.skipped_missing_catalog marketplaceListingId={}", workItem.getMarketplaceListingId());
            return;
        }

        ItemInventory inventory = itemInventoryDao.findByItemInventoryId(listing.getItemInventoryId()).orElse(null);
        String requestedCondition = bricklinkCondition(inventory);
        if (requestedCondition == null) {
            completeWorkItem(workItem, STATUS_SKIPPED_MISSING_CONDITION, "Missing item_inventory.new_or_used");
            counters.skippedListings++;
            return;
        }

        String itemNumber = clean(catalogItem.getExternalItemKey());
        if (itemNumber == null) {
            completeWorkItem(workItem, STATUS_SKIPPED_MISSING_ITEM_NUMBER, "Missing external_catalog_item.external_item_key");
            counters.skippedListings++;
            return;
        }

        Optional<Integer> resolvedItemId = resolveBricklinkInternalItemId(catalogItem, itemNumber, workItem, counters);
        if (resolvedItemId.isEmpty()) {
            counters.failedListings++;
            return;
        }

        crawlPricing(listing, catalogItem, inventory, workItem, requestedCondition, resolvedItemId.get(), counters);
    }

    private Optional<Integer> resolveBricklinkInternalItemId(
            ExternalCatalogItem catalogItem,
            String itemNumber,
            PricingCrawlWorkItem workItem,
            CrawlCounters counters
    ) {
        Optional<Integer> existingItemId = parseInternalItemId(catalogItem.getExternalUniqueKey());
        if (existingItemId.isPresent()) {
            return existingItemId;
        }

        try {
            Optional<Item> foundItem = bricklinkAjaxClient.findCatalogItem(itemNumber, effectiveCatalogItemType(catalogItem));
            if (foundItem.isEmpty()) {
                completeWorkItem(workItem, STATUS_FAILED_ITEM_ID_LOOKUP_NO_MATCH, "No exact BrickLink catalog item match for " + itemNumber);
                counters.catalogItemLookupNoMatches++;
                return Optional.empty();
            }

            Integer itemId = foundItem.get().getIdItem();
            catalogItem.setExternalUniqueKey(String.valueOf(itemId));
            externalCatalogItemDao.update(catalogItem);
            counters.hydratedCatalogItems++;
            return Optional.of(itemId);
        } catch (BricklinkAjaxClientException e) {
            if (e.getMessage() != null && e.getMessage().contains("Ambiguous")) {
                completeWorkItem(workItem, STATUS_FAILED_ITEM_ID_LOOKUP_AMBIGUOUS, e.getMessage());
                counters.catalogItemLookupAmbiguousMatches++;
            } else {
                retryOrFail(workItem, STATUS_FAILED_ITEM_ID_LOOKUP_HTTP_ERROR, e.getMessage());
                counters.catalogItemLookupFailures++;
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            retryOrFail(workItem, STATUS_FAILED_ITEM_ID_LOOKUP_HTTP_ERROR, e.getMessage());
            counters.catalogItemLookupFailures++;
            return Optional.empty();
        }
    }

    private void crawlPricing(
            MarketplaceListing listing,
            ExternalCatalogItem catalogItem,
            ItemInventory inventory,
            PricingCrawlWorkItem workItem,
            String requestedCondition,
            Integer itemId,
            CrawlCounters counters
    ) {
        Map<String, Object> requestParameters = Map.of(
                "itemid", itemId,
                "cond", requestedCondition,
                "rpp", properties.effectiveResultsPerPage(),
                "iconly", 0
        );
        workItem.setSourceRequestUrl(CATALOG_ITEMS_FOR_SALE_PATH);
        workItem.setSourceRequestParameters(writeJson(requestParameters));
        pricingCrawlWorkItemDao.update(workItem);

        try {
            CatalogItemsForSaleResult result = bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(
                    itemId,
                    requestedCondition,
                    properties.effectiveResultsPerPage()
            );
            persistPricingSnapshot(listing, catalogItem, inventory, workItem, requestedCondition, itemId, requestParameters, result, counters);
        } catch (JsonProcessingException e) {
            completeWorkItem(workItem, STATUS_FAILED_PRICING_PARSE_ERROR, e.getMessage());
            counters.failedListings++;
        } catch (RuntimeException e) {
            // bricklink-ajax returns an empty result when BrickLink has no lots for sale, so any exception here is a
            // real failure.
            retryOrFail(workItem, STATUS_FAILED_PRICING_HTTP_ERROR, e.getMessage());
            counters.failedListings++;
        }
    }

    private void persistPricingSnapshot(
            MarketplaceListing listing,
            ExternalCatalogItem catalogItem,
            ItemInventory inventory,
            PricingCrawlWorkItem workItem,
            String requestedCondition,
            Integer itemId,
            Map<String, Object> requestParameters,
            CatalogItemsForSaleResult result,
            CrawlCounters counters
    ) throws JsonProcessingException {
        String rawPayload = writeJson(result);
        PricingSnapshot snapshot = pricingSnapshotDao.insert(PricingSnapshot.builder()
                .pricingCrawlWorkItemId(workItem.getPricingCrawlWorkItemId())
                .marketplaceListingId(listing.getMarketplaceListingId())
                .externalCatalogItemId(catalogItem.getExternalCatalogItemId())
                .sourceExternalServiceId(properties.getBricklinkExternalServiceId())
                .sourceItemKey(catalogItem.getExternalItemKey())
                .sourceUniqueKey(String.valueOf(itemId))
                .itemConditionCode(requestedCondition)
                .completenessCode(inventory == null ? null : BricklinkPricingCodeNormalizer.completeness(inventory.getCompleteness()))
                .sourceRequestUrl(CATALOG_ITEMS_FOR_SALE_PATH)
                .sourceRequestParameters(writeJson(requestParameters))
                .rawPayloadHash(sha256(rawPayload))
                .comparableCount(result.getList().size())
                .capturedAt(now())
                .build());
        counters.snapshotsWritten++;
        if (result.getList().isEmpty()) {
            counters.zeroComparableSnapshotsWritten++;
        }

        for (ItemForSale itemForSale : result.getList()) {
            if (itemForSale.getDisplayCurrencyCode() == null || itemForSale.getSalePrice() == null || itemForSale.getSalePrice() <= 0) {
                log.warn(
                        "bricklink.pricing.crawl.unparseable_price pricingSnapshotId={} externalListingId={} displayPrice={}",
                        snapshot.getPricingSnapshotId(),
                        itemForSale.getIdInv(),
                        itemForSale.getMDisplaySalePrice()
                );
                continue;
            }
            pricingSnapshotListingDao.insert(snapshotListing(snapshot, itemForSale));
            counters.snapshotListingsWritten++;
        }

        completeWorkItem(workItem, STATUS_SUCCEEDED, null);
    }

    private PricingSnapshotListing snapshotListing(PricingSnapshot snapshot, ItemForSale itemForSale) throws JsonProcessingException {
        return PricingSnapshotListing.builder()
                .pricingSnapshotId(snapshot.getPricingSnapshotId())
                .externalListingId(itemForSale.getIdInv() == null ? null : String.valueOf(itemForSale.getIdInv()))
                .sellerName(itemForSale.getStrSellerUsername())
                .sellerCountryCode(itemForSale.getStrSellerCountryCode())
                .itemConditionCode(itemForSale.getCodeNew())
                .completenessCode(itemForSale.getCodeComplete())
                .quantityAvailable(itemForSale.getN4Qty())
                .unitPrice(BigDecimal.valueOf(itemForSale.getSalePrice()))
                .currencyCode(itemForSale.getDisplayCurrencyCode())
                .description(itemForSale.getStrDesc())
                .extendedDescription(null)
                .sourceListingPayload(objectMapper.writeValueAsString(itemForSale))
                .build();
    }

    private void completeWorkItem(PricingCrawlWorkItem workItem, String statusCode, String errorMessage) {
        workItem.setWorkStatusCode(statusCode);
        workItem.setCompletedAt(now());
        workItem.setClaimedAt(null);
        workItem.setNextAttemptAt(nextCrawlAttemptAt());
        workItem.setLastErrorMessage(errorMessage);
        pricingCrawlWorkItemDao.update(workItem);
    }

    private void retryOrFail(PricingCrawlWorkItem workItem, String failureStatusCode, String errorMessage) {
        if (canRetry(workItem)) {
            workItem.setWorkStatusCode(STATUS_PENDING);
            workItem.setClaimedAt(null);
            workItem.setCompletedAt(null);
            workItem.setNextAttemptAt(adjustForBlackout(now().plus(properties.effectiveRetryBackoff())));
            workItem.setLastErrorMessage(errorMessage);
            pricingCrawlWorkItemDao.update(workItem);
            return;
        }
        completeWorkItem(workItem, failureStatusCode, errorMessage);
    }

    private boolean canRetry(PricingCrawlWorkItem workItem) {
        int attemptCount = workItem.getAttemptCount() == null ? 0 : workItem.getAttemptCount();
        int maxAttempts = workItem.getMaxAttempts() == null ? properties.effectiveMaxAttempts() : workItem.getMaxAttempts();
        return attemptCount < maxAttempts;
    }

    private ZonedDateTime scheduledAttemptAt(ZonedDateTime runAt, int index, int listingCount) {
        Duration spreadWindow = properties.effectiveScheduleSpreadWindow();
        long spreadMillis = spreadWindow.toMillis();
        long spreadOffsetMillis = listingCount <= 1 ? 0 : (spreadMillis * index) / listingCount;
        long jitterMillis = randomJitterMillis();
        return adjustForBlackout(runAt.plus(Duration.ofMillis(spreadOffsetMillis + jitterMillis)));
    }

    private long randomJitterMillis() {
        long jitterMillis = properties.effectiveScheduleJitter().toMillis();
        if (jitterMillis <= 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextLong(jitterMillis + 1);
    }

    private ZonedDateTime nextCrawlAttemptAt() {
        return adjustForBlackout(now().plus(properties.effectiveCrawlCadence()));
    }

    private ZonedDateTime adjustForBlackout(ZonedDateTime attemptAt) {
        BricklinkPricingCrawlProperties.Blackout blackout = properties.getBlackout();
        if (blackout == null || !blackout.isEnabled()) {
            return attemptAt;
        }

        ZoneId zone = ZoneId.of(blackout.getZoneId());
        ZonedDateTime localAttempt = attemptAt.withZoneSameInstant(zone);
        if (blackout.isWeekdaysOnly() && isWeekend(localAttempt.getDayOfWeek())) {
            return attemptAt;
        }
        if (!isInBlackout(localAttempt, blackout)) {
            return attemptAt;
        }

        LocalDate nextAllowedDate = localAttempt.toLocalDate();
        if (!localAttempt.toLocalTime().isBefore(blackout.getEnd())) {
            nextAllowedDate = nextAllowedDate.plusDays(1);
        }
        ZonedDateTime nextAllowed = ZonedDateTime.of(nextAllowedDate, blackout.getEnd(), zone);
        return nextAllowed.withZoneSameInstant(ZoneOffset.UTC);
    }

    private boolean isInBlackout(ZonedDateTime localAttempt, BricklinkPricingCrawlProperties.Blackout blackout) {
        if (blackout.getStart().isBefore(blackout.getEnd())) {
            return !localAttempt.toLocalTime().isBefore(blackout.getStart())
                    && localAttempt.toLocalTime().isBefore(blackout.getEnd());
        }
        return !localAttempt.toLocalTime().isBefore(blackout.getStart())
                || localAttempt.toLocalTime().isBefore(blackout.getEnd());
    }

    private boolean isWeekend(DayOfWeek dayOfWeek) {
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }

    private String outcome(CrawlCounters counters) {
        if (counters.failedListings > 0) {
            return "PARTIAL_SUCCESS";
        }
        if (counters.workItemsClaimed > 0) {
            return "SUCCESS";
        }
        if (counters.workItemsScheduled > 0 || counters.staleWorkItemsRequeued > 0) {
            return "SCHEDULED";
        }
        return "NO_WORK";
    }

    private String bricklinkCondition(ItemInventory inventory) {
        if (inventory == null || inventory.getNewOrUsed() == null) {
            return null;
        }
        return BricklinkPricingCodeNormalizer.condition(inventory.getNewOrUsed());
    }

    private String effectiveCatalogItemType(ExternalCatalogItem catalogItem) {
        String itemTypeCode = clean(catalogItem.getItemTypeCode());
        if (itemTypeCode == null) {
            return properties.effectiveCatalogItemType();
        }
        return itemTypeCode.toUpperCase();
    }

    private Optional<Integer> parseInternalItemId(String externalUniqueKey) {
        try {
            return Optional.ofNullable(clean(externalUniqueKey)).map(Integer::valueOf);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize pricing crawl value", e);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    private long elapsedMillis(long start) {
        return System.currentTimeMillis() - start;
    }

    private static final class CrawlCounters {
        private int listingsSelected;
        private int workItemsScheduled;
        private int workItemsClaimed;
        private int staleWorkItemsRequeued;
        private int snapshotsWritten;
        private int zeroComparableSnapshotsWritten;
        private int snapshotListingsWritten;
        private int hydratedCatalogItems;
        private int catalogItemLookupNoMatches;
        private int catalogItemLookupAmbiguousMatches;
        private int catalogItemLookupFailures;
        private int skippedListings;
        private int failedListings;
    }
}
