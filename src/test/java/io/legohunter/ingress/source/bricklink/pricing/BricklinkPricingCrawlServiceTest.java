package io.legohunter.ingress.source.bricklink.pricing;

import com.bricklink.api.ajax.BricklinkAjaxClient;
import com.bricklink.api.ajax.exception.BricklinkAjaxClientException;
import com.bricklink.api.ajax.model.v1.Item;
import com.bricklink.api.ajax.model.v1.ItemForSale;
import com.bricklink.api.ajax.support.CatalogItemsForSaleResult;
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
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingCrawlServiceTest {
    private BricklinkAjaxClient bricklinkAjaxClient;
    private MarketplaceListingDao marketplaceListingDao;
    private ExternalCatalogItemDao externalCatalogItemDao;
    private ItemInventoryDao itemInventoryDao;
    private PricingCrawlWorkItemDao pricingCrawlWorkItemDao;
    private PricingSnapshotDao pricingSnapshotDao;
    private PricingSnapshotListingDao pricingSnapshotListingDao;
    private BricklinkPricingCrawlProperties properties;
    private BricklinkPricingCrawlService service;

    @BeforeEach
    void setUp() {
        bricklinkAjaxClient = mock(BricklinkAjaxClient.class);
        marketplaceListingDao = mock(MarketplaceListingDao.class);
        externalCatalogItemDao = mock(ExternalCatalogItemDao.class);
        itemInventoryDao = mock(ItemInventoryDao.class);
        pricingCrawlWorkItemDao = mock(PricingCrawlWorkItemDao.class);
        pricingSnapshotDao = mock(PricingSnapshotDao.class);
        pricingSnapshotListingDao = mock(PricingSnapshotListingDao.class);

        properties = new BricklinkPricingCrawlProperties();
        properties.setEnabled(true);
        properties.setBatchSize(10);
        properties.setWorkerBatchSize(1);
        properties.setScheduleSpreadWindow(Duration.ZERO);
        properties.setResultsPerPage(500);

        when(pricingCrawlWorkItemDao.insert(any())).thenAnswer(invocation -> {
            PricingCrawlWorkItem workItem = invocation.getArgument(0);
            workItem.setPricingCrawlWorkItemId(100L);
            return workItem;
        });
        when(pricingCrawlWorkItemDao.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(pricingSnapshotDao.insert(any())).thenAnswer(invocation -> {
            PricingSnapshot snapshot = invocation.getArgument(0);
            snapshot.setPricingSnapshotId(200L);
            return snapshot;
        });
        when(pricingSnapshotListingDao.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(externalCatalogItemDao.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new BricklinkPricingCrawlService(
                bricklinkAjaxClient,
                properties,
                marketplaceListingDao,
                externalCatalogItemDao,
                itemInventoryDao,
                pricingCrawlWorkItemDao,
                pricingSnapshotDao,
                pricingSnapshotListingDao,
                new ObjectMapper()
        );
    }

    @Test
    void runOnceHydratesMissingInternalItemIdAndPersistsSnapshotListings() {
        ExternalCatalogItem catalogItem = catalogItem(null);
        MarketplaceListing listing = listing(catalogItem);
        givenScheduledAndClaimed(listing);
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("USED", "COMPLETE")));
        when(bricklinkAjaxClient.findCatalogItem("6390-1", "S")).thenReturn(Optional.of(searchItem(4997)));
        when(bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(4997, "U", 500))
                .thenReturn(catalogResult(itemForSale(3001, "seller1", "US $220.00")));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.listingsSelected()).isOne();
        assertThat(result.workItemsScheduled()).isOne();
        assertThat(result.workItemsClaimed()).isOne();
        assertThat(result.hydratedCatalogItems()).isOne();
        assertThat(result.catalogItemLookupNoMatches()).isZero();
        assertThat(result.catalogItemLookupAmbiguousMatches()).isZero();
        assertThat(result.catalogItemLookupFailures()).isZero();
        assertThat(result.snapshotsWritten()).isOne();
        assertThat(result.snapshotListingsWritten()).isOne();
        assertThat(catalogItem.getExternalUniqueKey()).isEqualTo("4997");
        ArgumentCaptor<PricingCrawlWorkItem> workItemCaptor = ArgumentCaptor.forClass(PricingCrawlWorkItem.class);
        verify(pricingCrawlWorkItemDao).insert(workItemCaptor.capture());
        assertThat(workItemCaptor.getValue().getNextAttemptAt()).isNotNull();
        assertThat(workItemCaptor.getValue().getClaimedAt()).isNull();
        assertThat(workItemCaptor.getValue().getWorkStatusCode()).isEqualTo(BricklinkPricingCrawlService.STATUS_PENDING);
        verify(externalCatalogItemDao).update(catalogItem);
        verify(bricklinkAjaxClient).catalogItemsForSaleByInternalItemId(4997, "U", 500);
        verify(pricingSnapshotDao).insert(any(PricingSnapshot.class));
        verify(pricingSnapshotListingDao).insert(any(PricingSnapshotListing.class));
    }

    @Test
    void runOnceUsesCatalogItemTypeWhenHydratingMissingInternalItemId() {
        ExternalCatalogItem catalogItem = catalogItem(null, "KC140", "G");
        MarketplaceListing listing = listing(catalogItem);
        givenScheduledAndClaimed(listing);
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("NEW", "SEALED")));
        when(bricklinkAjaxClient.findCatalogItem("KC140", "G")).thenReturn(Optional.of(searchItem(39975)));
        when(bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(39975, "N", 500))
                .thenReturn(catalogResult(itemForSale(3001, "seller1", "US $3.99")));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.hydratedCatalogItems()).isOne();
        assertThat(catalogItem.getExternalUniqueKey()).isEqualTo("39975");
        verify(bricklinkAjaxClient).findCatalogItem("KC140", "G");
        verify(bricklinkAjaxClient).catalogItemsForSaleByInternalItemId(39975, "N", 500);
    }

    @Test
    void runOnceFallsBackToConfiguredCatalogItemTypeWhenCatalogItemTypeIsBlank() {
        ExternalCatalogItem catalogItem = catalogItem(null, "6390-1", " ");
        MarketplaceListing listing = listing(catalogItem);
        givenScheduledAndClaimed(listing);
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("USED", "COMPLETE")));
        when(bricklinkAjaxClient.findCatalogItem("6390-1", "S")).thenReturn(Optional.of(searchItem(4997)));
        when(bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(4997, "U", 500))
                .thenReturn(catalogResult(itemForSale(3001, "seller1", "US $220.00")));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.hydratedCatalogItems()).isOne();
        assertThat(catalogItem.getExternalUniqueKey()).isEqualTo("4997");
        verify(bricklinkAjaxClient).findCatalogItem("6390-1", "S");
    }

    @Test
    void runOnceSkipsCatalogSearchWhenInternalItemIdAlreadyExists() {
        ExternalCatalogItem catalogItem = catalogItem("4997");
        MarketplaceListing listing = listing(catalogItem);
        givenScheduledAndClaimed(listing);
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("USED", "COMPLETE")));
        when(bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(4997, "U", 500))
                .thenReturn(catalogResult(itemForSale(3001, "seller1", "US $220.00")));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.hydratedCatalogItems()).isZero();
        assertThat(result.snapshotsWritten()).isOne();
        verify(bricklinkAjaxClient, never()).findCatalogItem(any(), any());
        verify(externalCatalogItemDao, never()).update(any());
    }

    @Test
    void runOncePersistsAllReturnedRowsWhenCompletenessIsMixed() {
        ExternalCatalogItem catalogItem = catalogItem("6418");
        MarketplaceListing listing = listing(catalogItem);
        givenScheduledAndClaimed(listing);
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("N", "S")));
        when(bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(6418, "N", 500))
                .thenReturn(catalogResult(
                        itemForSale(3001, "sealed-seller", "US $30.00", "N", "S"),
                        itemForSale(3002, "complete-seller", "US $20.00", "N", "C")
                ));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.snapshotsWritten()).isOne();
        assertThat(result.snapshotListingsWritten()).isEqualTo(2);
        ArgumentCaptor<PricingSnapshot> snapshotCaptor = ArgumentCaptor.forClass(PricingSnapshot.class);
        verify(pricingSnapshotDao).insert(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().getItemConditionCode()).isEqualTo("N");
        assertThat(snapshotCaptor.getValue().getCompletenessCode()).isEqualTo("S");
        assertThat(snapshotCaptor.getValue().getComparableCount()).isEqualTo(2);

        ArgumentCaptor<PricingSnapshotListing> listingCaptor = ArgumentCaptor.forClass(PricingSnapshotListing.class);
        verify(pricingSnapshotListingDao, org.mockito.Mockito.times(2)).insert(listingCaptor.capture());
        assertThat(listingCaptor.getAllValues())
                .extracting(PricingSnapshotListing::getCompletenessCode)
                .containsExactlyInAnyOrder("S", "C");
    }

    @Test
    void runOncePersistsZeroComparableSnapshotWhenBricklinkHasNoLotsForSale() {
        ExternalCatalogItem catalogItem = catalogItem("4997");
        MarketplaceListing listing = listing(catalogItem);
        givenScheduledAndClaimed(listing);
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("USED", "COMPLETE")));
        // bricklink-ajax turns BrickLink's "no lots" 400 into an empty result
        when(bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(4997, "U", 500))
                .thenReturn(new CatalogItemsForSaleResult());

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.failedListings()).isZero();
        assertThat(result.snapshotsWritten()).isOne();
        assertThat(result.zeroComparableSnapshotsWritten()).isOne();
        assertThat(result.snapshotListingsWritten()).isZero();

        ArgumentCaptor<PricingSnapshot> snapshotCaptor = ArgumentCaptor.forClass(PricingSnapshot.class);
        verify(pricingSnapshotDao).insert(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().getComparableCount()).isZero();
        assertThat(snapshotCaptor.getValue().getItemConditionCode()).isEqualTo("U");
        assertThat(snapshotCaptor.getValue().getCompletenessCode()).isEqualTo("C");
        verify(pricingSnapshotListingDao, never()).insert(any());
    }

    @Test
    void runOnceRetriesWhenTheCrawlCallFailsEvenIfTheErrorTextLooksEmpty() {
        ExternalCatalogItem catalogItem = catalogItem("4997");
        MarketplaceListing listing = listing(catalogItem);
        givenScheduledAndClaimed(listing);
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("USED", "COMPLETE")));
        when(bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(4997, "U", 500))
                .thenThrow(new BricklinkAjaxClientException(
                        429,
                        "GET https://www.bricklink.com/ajax/clone/catalogifs.ajax?itemid=4997&iconly=0&rpp=500&pi=1&cond=U returned []"
                ));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.failedListings()).isOne();
        verify(pricingSnapshotDao, never()).insert(any(PricingSnapshot.class));
    }

    @Test
    void runOnceSkipsLotsWhosePriceCannotBeRead() {
        ExternalCatalogItem catalogItem = catalogItem("4997");
        MarketplaceListing listing = listing(catalogItem);
        givenScheduledAndClaimed(listing);
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("USED", "COMPLETE")));
        CatalogItemsForSaleResult forSale = new CatalogItemsForSaleResult();
        forSale.setList(new java.util.ArrayList<>(List.of(itemForSale(101, "seller-a", "US $20.00"), itemForSale(102, "seller-b", "about 20 dollars"))));
        when(bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(4997, "U", 500)).thenReturn(forSale);

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.snapshotListingsWritten()).isOne();
        ArgumentCaptor<PricingSnapshotListing> listingCaptor = ArgumentCaptor.forClass(PricingSnapshotListing.class);
        verify(pricingSnapshotListingDao).insert(listingCaptor.capture());
        assertThat(listingCaptor.getValue().getExternalListingId()).isEqualTo("101");
        assertThat(listingCaptor.getValue().getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void runOnceRecordsNoMatchLookupFailureWithoutCallingPricingEndpoint() {
        ExternalCatalogItem catalogItem = catalogItem(null);
        MarketplaceListing listing = listing(catalogItem);
        givenScheduledAndClaimed(listing);
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("USED", "COMPLETE")));
        when(bricklinkAjaxClient.findCatalogItem("6390-1", "S")).thenReturn(Optional.empty());

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.failedListings()).isOne();
        assertThat(result.catalogItemLookupNoMatches()).isOne();
        verify(bricklinkAjaxClient, never()).catalogItemsForSaleByInternalItemId(any(), any(), any());
        verify(pricingCrawlWorkItemDao).update(any(PricingCrawlWorkItem.class));
        verify(pricingSnapshotDao, never()).insert(any());
    }

    @Test
    void runOnceSkipsMissingInventoryConditionBeforeAjaxCalls() {
        ExternalCatalogItem catalogItem = catalogItem(null);
        MarketplaceListing listing = listing(catalogItem);
        givenScheduledAndClaimed(listing);
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory(null, "COMPLETE")));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.skippedListings()).isOne();
        verify(bricklinkAjaxClient, never()).findCatalogItem(any(), any());
        verify(bricklinkAjaxClient, never()).catalogItemsForSaleByInternalItemId(any(), any(), any());
    }

    @Test
    void runOnceRequeuesRetryablePricingFailureWhenAttemptsRemain() {
        ExternalCatalogItem catalogItem = catalogItem("4997");
        MarketplaceListing listing = listing(catalogItem);
        givenScheduledAndClaimed(listing);
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("USED", "COMPLETE")));
        when(bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(4997, "U", 500))
                .thenThrow(new RuntimeException("BrickLink temporarily unavailable"));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("PARTIAL_SUCCESS");
        ArgumentCaptor<PricingCrawlWorkItem> workItemCaptor = ArgumentCaptor.forClass(PricingCrawlWorkItem.class);
        verify(pricingCrawlWorkItemDao, atLeastOnce()).update(workItemCaptor.capture());
        PricingCrawlWorkItem finalUpdate = workItemCaptor.getAllValues().getLast();
        assertThat(finalUpdate.getWorkStatusCode()).isEqualTo(BricklinkPricingCrawlService.STATUS_PENDING);
        assertThat(finalUpdate.getClaimedAt()).isNull();
        assertThat(finalUpdate.getCompletedAt()).isNull();
        assertThat(finalUpdate.getNextAttemptAt()).isNotNull();
        assertThat(finalUpdate.getLastErrorMessage()).isEqualTo("BrickLink temporarily unavailable");
    }

    @Test
    void runOnceSchedulesButDoesNotProcessWhenNoWorkIsDue() {
        ExternalCatalogItem catalogItem = catalogItem("4997");
        MarketplaceListing listing = listing(catalogItem);
        when(marketplaceListingDao.findPricingCrawlSchedulingCandidatesByListingExternalServiceIdAndListingStatusCodes(
                any(), any(), any(), any(), any(), any(Integer.class)
        )).thenReturn(Set.of(listing));
        when(pricingCrawlWorkItemDao.claimDueWorkItems(any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(Set.of());

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SCHEDULED");
        assertThat(result.workItemsScheduled()).isOne();
        assertThat(result.workItemsClaimed()).isZero();
        verify(bricklinkAjaxClient, never()).catalogItemsForSaleByInternalItemId(any(), any(), any());
    }

    @Test
    void runOnceDoesNotScheduleListingsOutsideAllowlist() {
        properties.setMarketplaceListingAllowlist(Set.of(999));
        ExternalCatalogItem catalogItem = catalogItem("4997");
        MarketplaceListing listing = listing(catalogItem);
        when(marketplaceListingDao.findPricingCrawlSchedulingCandidatesByListingExternalServiceIdAndListingStatusCodes(
                any(), any(), any(), any(), any(), any(Integer.class)
        )).thenReturn(Set.of(listing));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_WORK");
        verify(pricingCrawlWorkItemDao, never()).insert(any());
        verify(bricklinkAjaxClient, never()).catalogItemsForSaleByInternalItemId(any(), any(), any());
    }

    private void givenScheduledAndClaimed(MarketplaceListing listing) {
        when(marketplaceListingDao.findPricingCrawlSchedulingCandidatesByListingExternalServiceIdAndListingStatusCodes(
                any(), any(), any(), any(), any(), any(Integer.class)
        )).thenReturn(Set.of(listing));
        when(pricingCrawlWorkItemDao.claimDueWorkItems(any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(Set.of(claimedWorkItem(listing)));
        when(marketplaceListingDao.findByMarketplaceListingId(listing.getMarketplaceListingId()))
                .thenReturn(Optional.of(listing));
    }

    private PricingCrawlWorkItem claimedWorkItem(MarketplaceListing listing) {
        return PricingCrawlWorkItem.builder()
                .pricingCrawlWorkItemId(100L)
                .marketplaceListingId(listing.getMarketplaceListingId())
                .externalCatalogItemId(listing.getExternalCatalogItemId())
                .sourceExternalServiceId(2)
                .workStatusCode(BricklinkPricingCrawlService.STATUS_CLAIMED)
                .attemptCount(1)
                .maxAttempts(3)
                .build();
    }

    private MarketplaceListing listing(ExternalCatalogItem catalogItem) {
        return MarketplaceListing.builder()
                .marketplaceListingId(10)
                .itemInventoryId(20)
                .listingExternalServiceId(2)
                .externalCatalogItemId(30)
                .externalListingId("BL-INV-10")
                .listingStatusCode("ACTIVE")
                .unitPrice(new BigDecimal("250.00"))
                .currencyCode("USD")
                .externalCatalogItem(catalogItem)
                .build();
    }

    private ExternalCatalogItem catalogItem(String externalUniqueKey) {
        return catalogItem(externalUniqueKey, "6390-1", "S");
    }

    private ExternalCatalogItem catalogItem(String externalUniqueKey, String externalItemKey, String itemTypeCode) {
        return ExternalCatalogItem.builder()
                .externalCatalogItemId(30)
                .externalServiceId(2)
                .externalItemKey(externalItemKey)
                .externalUniqueKey(externalUniqueKey)
                .itemName("Main Street")
                .itemTypeCode(itemTypeCode)
                .build();
    }

    private ItemInventory inventory(String newOrUsed, String completeness) {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(20);
        inventory.setNewOrUsed(newOrUsed);
        inventory.setCompleteness(completeness);
        return inventory;
    }

    private Item searchItem(Integer idItem) {
        Item item = new Item();
        item.setIdItem(idItem);
        item.setTypeItem("S");
        item.setStrItemNo("6390-1");
        item.setStrItemName("Main Street");
        return item;
    }

    private CatalogItemsForSaleResult catalogResult(ItemForSale... listings) {
        CatalogItemsForSaleResult result = new CatalogItemsForSaleResult();
        result.setTotal_count(listings.length);
        result.setRpp(500);
        result.setPi(1);
        result.setList(List.of(listings));
        return result;
    }

    private ItemForSale itemForSale(Integer idInv, String sellerName, String displayPrice) {
        return itemForSale(idInv, sellerName, displayPrice, "U", "C");
    }

    private ItemForSale itemForSale(Integer idInv, String sellerName, String displayPrice, String condition, String completeness) {
        ItemForSale itemForSale = new ItemForSale();
        itemForSale.setIdInv(idInv);
        itemForSale.setStrSellerUsername(sellerName);
        itemForSale.setStrSellerCountryCode("US");
        itemForSale.setCodeNew(condition);
        itemForSale.setCodeComplete(completeness);
        itemForSale.setN4Qty(1);
        itemForSale.setMDisplaySalePrice(displayPrice);
        itemForSale.setStrDesc("Complete used set");
        return itemForSale;
    }
}
