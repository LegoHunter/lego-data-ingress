package io.legohunter.ingress.source.bricklink.pricing;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.lego.data.environment.LegoEnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BricklinkInventoryReconciliationServiceTest {
    private final BricklinkInventoryReconciliationProperties properties = new BricklinkInventoryReconciliationProperties();
    private final LegoEnvironmentProperties environment = new LegoEnvironmentProperties();
    private final BricklinkRestClient bricklinkRestClient = mock(BricklinkRestClient.class);
    private final MarketplaceListingDao marketplaceListingDao = mock(MarketplaceListingDao.class);
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao = mock(BricklinkMarketplaceListingDao.class);
    private final MarketplaceListingSyncRequestDao syncRequestDao = mock(MarketplaceListingSyncRequestDao.class);
    private final BricklinkInventoryReconciliationService service = new BricklinkInventoryReconciliationService(
            properties, environment, bricklinkRestClient, marketplaceListingDao, bricklinkMarketplaceListingDao, syncRequestDao);

    @BeforeEach
    void runAsSandbox() {
        environment.setCode("sandbox");
        environment.setProduction(false);
        when(syncRequestDao.findByMarketplaceListingId(anyInt())).thenReturn(Set.of());
    }

    @Test
    void storeThatMatchesTheListingsIsInSync() {
        arrangeListings(listing(956, "70.14"), listing(957, "99.40"));
        arrangeBricklink(mapping(956, 1001), mapping(957, 1002));
        arrangeStore(lot(1001, 70.14, 956), lot(1002, 99.40, 957), prodLotWithoutBlock(5000));

        BricklinkInventoryReconciliationReport report = service.reconcile();

        assertThat(report.outcome()).isEqualTo("IN_SYNC");
        assertThat(report.remoteLots()).isEqualTo(3);
        assertThat(report.ownedRemoteLots()).isEqualTo(2);
        assertThat(report.localListings()).isEqualTo(2);
        assertThat(report.matchedListings()).isEqualTo(2);
        assertThat(report.drifts()).isEmpty();
        assertThat(report.driftCounts()).hasSize(BricklinkInventoryDriftType.values().length).allSatisfy((type, count) -> assertThat(count).isZero());
    }

    @Test
    void reportsAListingWhoseLotIsGone() {
        arrangeListings(listing(956, "70.14"));
        arrangeBricklink(mapping(956, 1001));
        arrangeStore();

        BricklinkInventoryReconciliationReport report = service.reconcile();

        assertSingleDrift(report, BricklinkInventoryDriftType.MISSING_REMOTE_LOT, 956, 1001L);
    }

    @Test
    void reportsAPriceChangedByHandOnBricklink() {
        arrangeListings(listing(956, "70.14"));
        arrangeBricklink(mapping(956, 1001));
        arrangeStore(lot(1001, 999.00, 956));

        BricklinkInventoryReconciliationReport report = service.reconcile();

        BricklinkInventoryReconciliationReport.Drift drift = assertSingleDrift(report, BricklinkInventoryDriftType.PRICE_DRIFT, 956, 1001L);
        assertThat(drift.expected()).isEqualTo("70.14");
        assertThat(drift.actual()).isEqualTo("999.00");
    }

    @Test
    void doesNotReportPriceDriftWhileAPriceChangeIsInFlight() {
        arrangeListings(listing(956, "70.14"));
        arrangeBricklink(mapping(956, 1001));
        arrangeStore(lot(1001, 75.00, 956));
        when(syncRequestDao.findByMarketplaceListingId(956)).thenReturn(Set.of(
                MarketplaceListingSyncRequest.builder().marketplaceListingSyncRequestId(1L).syncRequestStatusCode("CLAIMED").build()));

        BricklinkInventoryReconciliationReport report = service.reconcile();

        assertThat(report.outcome()).isEqualTo("IN_SYNC");
        assertThat(report.inFlightListings()).isOne();
    }

    @Test
    void stillReportsPriceDriftAfterTheSyncRequestFinished() {
        arrangeListings(listing(956, "70.14"));
        arrangeBricklink(mapping(956, 1001));
        arrangeStore(lot(1001, 75.00, 956));
        when(syncRequestDao.findByMarketplaceListingId(956)).thenReturn(Set.of(
                MarketplaceListingSyncRequest.builder().marketplaceListingSyncRequestId(1L).syncRequestStatusCode("BLOCKED").build()));

        assertSingleDrift(service.reconcile(), BricklinkInventoryDriftType.PRICE_DRIFT, 956, 1001L);
    }

    @Test
    void reportsAQuantityChange() {
        arrangeListings(listing(956, "70.14"));
        arrangeBricklink(mapping(956, 1001));
        Inventory lot = lot(1001, 70.14, 956);
        lot.setQuantity(3);
        arrangeStore(lot);

        assertSingleDrift(service.reconcile(), BricklinkInventoryDriftType.QUANTITY_DRIFT, 956, 1001L);
    }

    @Test
    void reportsASandboxLotThatLeftItsStockroom() {
        arrangeListings(listing(956, "70.14"), listing(957, "99.40"));
        arrangeBricklink(mapping(956, 1001), mapping(957, 1002));
        Inventory publicLot = lot(1001, 70.14, 956);
        publicLot.setIs_stock_room(false);
        publicLot.setStock_room_id(null);
        Inventory stockroomB = lot(1002, 99.40, 957);
        stockroomB.setStock_room_id("B");
        arrangeStore(publicLot, stockroomB);

        BricklinkInventoryReconciliationReport report = service.reconcile();

        assertThat(report.driftCounts().get(BricklinkInventoryDriftType.VISIBILITY_DRIFT)).isEqualTo(2);
        assertThat(report.drifts()).extracting(BricklinkInventoryReconciliationReport.Drift::actual)
                .containsExactlyInAnyOrder("public", "stockroom B");
    }

    @Test
    void reportsALotWhoseSystemBlockIsMissingOrNamesAnotherListing() {
        arrangeListings(listing(956, "70.14"), listing(957, "99.40"));
        arrangeBricklink(mapping(956, 1001), mapping(957, 1002));
        Inventory noBlock = lot(1001, 70.14, 956);
        noBlock.setRemarks("Edited by hand");
        arrangeStore(noBlock, lot(1002, 99.40, 999));

        BricklinkInventoryReconciliationReport report = service.reconcile();

        assertThat(report.driftCounts().get(BricklinkInventoryDriftType.REMARKS_DRIFT)).isEqualTo(2);
        // Listing 957 points at the lot whose block names listing 999, so that lot is tracked, not untracked.
        assertThat(report.driftCounts().get(BricklinkInventoryDriftType.UNTRACKED_REMOTE_LOT)).isZero();
    }

    @Test
    void reportsALotTaggedForThisEnvironmentThatNoListingOwns() {
        arrangeListings();
        arrangeBricklink();
        arrangeStore(lot(1003, 50.00, 958), lotForEnvironment(1004, "dev", 958));

        BricklinkInventoryReconciliationReport report = service.reconcile();

        BricklinkInventoryReconciliationReport.Drift drift = assertSingleDrift(report, BricklinkInventoryDriftType.UNTRACKED_REMOTE_LOT, 958, 1003L);
        assertThat(drift.actual()).contains("958");
        // The DEV lot belongs to another environment's reconciliation.
        assertThat(report.ownedRemoteLots()).isOne();
    }

    @Test
    void ignoresListingsThatAreNotActiveOrHaveNoLot() {
        MarketplaceListing ended = listing(956, "70.14");
        ended.setListingStatusCode("INACTIVE");
        MarketplaceListing draft = listing(957, "99.40");
        draft.setListingStatusCode("DRAFT");
        arrangeListings(ended, draft, listing(958, "10.00"));
        arrangeBricklink(mapping(956, 1001), mapping(957, 1002), BricklinkMarketplaceListing.builder().marketplaceListingId(958).build());
        arrangeStore();

        BricklinkInventoryReconciliationReport report = service.reconcile();

        assertThat(report.localListings()).isZero();
        assertThat(report.outcome()).isEqualTo("IN_SYNC");
    }

    @Test
    void storeReadFailureIsReportedAndComparesNothing() {
        when(bricklinkRestClient.getInventories(anyMap())).thenThrow(new IllegalStateException("BrickLink returned 401"));

        BricklinkInventoryReconciliationReport report = service.reconcile();

        assertThat(report.outcome()).isEqualTo("FAILED");
        assertThat(report.errorMessage()).contains("401");
        assertThat(report.drifts()).isEmpty();
        verify(bricklinkRestClient).getInventories(anyMap());
        verifyNoMoreInteractions(marketplaceListingDao, bricklinkMarketplaceListingDao);
    }

    @Test
    void emptyStoreResponseIsAFailureNotAnEmptyStore() {
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(new BricklinkResource<>());

        assertThat(service.reconcile().outcome()).isEqualTo("FAILED");
    }

    @Test
    void prodComparesVisibilityWithWhatTheListingRecorded() {
        environment.setCode("prod");
        environment.setProduction(true);
        arrangeListings(listing(956, "70.14"), listing(957, "99.40"));
        BricklinkMarketplaceListing publicMapping = mapping(956, 1001);
        publicMapping.setIsStockRoom(false);
        publicMapping.setStockRoomId(null);
        BricklinkMarketplaceListing hiddenMapping = mapping(957, 1002);
        hiddenMapping.setStockRoomId("A");
        arrangeBricklink(publicMapping, hiddenMapping);
        Inventory publicLot = lotForEnvironment(1001, "prod", 956);
        publicLot.setUnit_price(70.14);
        publicLot.setIs_stock_room(false);
        publicLot.setStock_room_id(null);
        Inventory madePublic = lotForEnvironment(1002, "prod", 957);
        madePublic.setUnit_price(99.40);
        madePublic.setIs_stock_room(false);
        arrangeStore(publicLot, madePublic);

        BricklinkInventoryReconciliationReport report = service.reconcile();

        BricklinkInventoryReconciliationReport.Drift drift = assertSingleDrift(report, BricklinkInventoryDriftType.VISIBILITY_DRIFT, 957, 1002L);
        assertThat(drift.expected()).isEqualTo("stockroom A");
        assertThat(drift.actual()).isEqualTo("public");
    }

    private BricklinkInventoryReconciliationReport.Drift assertSingleDrift(
            BricklinkInventoryReconciliationReport report, BricklinkInventoryDriftType type, Integer listingId, Long lotId) {
        assertThat(report.outcome()).isEqualTo("DRIFT_FOUND");
        assertThat(report.drifts()).singleElement().satisfies(drift -> {
            assertThat(drift.type()).isEqualTo(type);
            assertThat(drift.marketplaceListingId()).isEqualTo(listingId);
            assertThat(drift.bricklinkInventoryId()).isEqualTo(lotId);
        });
        assertThat(report.driftCounts().get(type)).isOne();
        return report.drifts().getFirst();
    }

    private void arrangeListings(MarketplaceListing... listings) {
        when(marketplaceListingDao.findAll()).thenReturn(Set.of(listings));
    }

    private void arrangeBricklink(BricklinkMarketplaceListing... mappings) {
        when(bricklinkMarketplaceListingDao.findAll()).thenReturn(Set.of(mappings));
    }

    private void arrangeStore(Inventory... lots) {
        BricklinkResource<List<Inventory>> resource = new BricklinkResource<>();
        resource.setData(Arrays.asList(lots));
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(resource);
    }

    private MarketplaceListing listing(int id, String price) {
        return MarketplaceListing.builder()
                .marketplaceListingId(id)
                .listingExternalServiceId(2)
                .listingStatusCode("ACTIVE")
                .unitPrice(new BigDecimal(price))
                .build();
    }

    private BricklinkMarketplaceListing mapping(int listingId, int lotId) {
        return BricklinkMarketplaceListing.builder()
                .marketplaceListingId(listingId)
                .bricklinkInventoryId(lotId)
                .isStockRoom(true)
                .stockRoomId("C")
                .lastRemoteQuantity(1)
                .build();
    }

    private Inventory lot(long id, double price, int listingId) {
        Inventory lot = lotForEnvironment(id, "sandbox", listingId);
        lot.setUnit_price(price);
        return lot;
    }

    private Inventory lotForEnvironment(long id, String environmentCode, int listingId) {
        Inventory lot = new Inventory();
        lot.setInventory_id(id);
        lot.setQuantity(1);
        lot.setIs_stock_room(true);
        lot.setStock_room_id("C");
        lot.setRemarks("[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=" + environmentCode
                + "; MARKETPLACE_LISTING_ID=" + listingId + "; ITEM_INVENTORY_UUID=uuid-" + listingId + " [SYSTEM_END]");
        return lot;
    }

    private Inventory prodLotWithoutBlock(long id) {
        Inventory lot = new Inventory();
        lot.setInventory_id(id);
        lot.setQuantity(1);
        lot.setIs_stock_room(false);
        lot.setRemarks("Listed by the legacy tools");
        return lot;
    }
}
