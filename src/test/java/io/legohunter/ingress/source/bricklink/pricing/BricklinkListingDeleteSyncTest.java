package io.legohunter.ingress.source.bricklink.pricing;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.exception.BricklinkClientException;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LISTING_DELETE (HOMELAB-40 teardown): deletes only this environment's own stockroom lot, never in
 * PROD, and counts it done only when BrickLink no longer has the lot.
 */
class BricklinkListingDeleteSyncTest {
    private static final long LOT = 12345L;

    private final BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();
    private final MarketplaceListingSyncRequestDao syncRequestDao = mock(MarketplaceListingSyncRequestDao.class);
    private final MarketplaceListingDao marketplaceListingDao = mock(MarketplaceListingDao.class);
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao = mock(BricklinkMarketplaceListingDao.class);
    private final ItemInventoryDao itemInventoryDao = mock(ItemInventoryDao.class);
    private final BricklinkRestClient bricklinkRestClient = mock(BricklinkRestClient.class);
    private final CountingTransactions transactions = new CountingTransactions();
    private final BricklinkMarketplaceSyncService service = new BricklinkMarketplaceSyncService(
            properties,
            syncRequestDao,
            marketplaceListingDao,
            bricklinkMarketplaceListingDao,
            itemInventoryDao,
            bricklinkRestClient,
            new BricklinkRemoteInventorySafetyService(),
            new BricklinkListingCreateSafetyService(),
            new BricklinkListingCreateInventoryMapper(),
            mock(PricingDecisionDao.class),
            transactions
    );

    @BeforeEach
    void sandboxInApplyMode() {
        properties.setEnabled(true);
        properties.setMode("APPLY");
        properties.setEnvironmentCode("sandbox");
        properties.setNonProdStockRoomId("C");
        when(syncRequestDao.update(any(MarketplaceListingSyncRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketplaceListingDao.update(any(MarketplaceListing.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(bricklinkMarketplaceListingDao.update(any(BricklinkMarketplaceListing.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void deletesTheSandboxLotThenEndsTheListingOnceBricklinkNoLongerHasIt() {
        arrange(mapping(12345));
        when(bricklinkRestClient.getInventories(LOT))
                .thenReturn(resource(lot("C", systemBlock("sandbox"))))
                .thenThrow(notFound());

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.remoteUpdated()).isOne();
        assertThat(result.remoteVerified()).isOne();
        assertThat(result.blocked()).isZero();
        InOrder order = inOrder(bricklinkRestClient, marketplaceListingDao);
        order.verify(bricklinkRestClient).getInventories(LOT);
        order.verify(bricklinkRestClient).deleteInventory(LOT);
        order.verify(bricklinkRestClient).getInventories(LOT);
        order.verify(marketplaceListingDao).update(any(MarketplaceListing.class));
        assertListingEnded();
        MarketplaceListingSyncRequest finished = lastRequestUpdate();
        assertThat(finished.getSyncRequestStatusCode()).isEqualTo("SUCCEEDED");
        assertThat(finished.getRemoteInventoryId()).isEqualTo("12345");
        assertThat(finished.getRemoteIsPubliclyAvailable()).isFalse();
    }

    @Test
    void isRefusedInProdWithoutTouchingBricklink() {
        properties.setProduction(true);
        properties.setEnvironmentCode("prod");
        arrange(mapping(12345));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        verify(bricklinkRestClient, never()).getInventories(anyLong());
        verify(bricklinkRestClient, never()).deleteInventory(anyLong());
        assertThat(lastRequestUpdate().getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        assertThat(lastRequestUpdate().getLastErrorMessage()).startsWith("LISTING_DELETE_REFUSED_IN_PROD");
    }

    @Test
    void refusesALotInAnotherStockroom() {
        assertRefused(lot("B", systemBlock("sandbox")), "REMOTE_STOCKROOM_MISMATCH");
    }

    @Test
    void refusesAPublicLot() {
        Inventory publicLot = lot("C", systemBlock("sandbox"));
        publicLot.setIs_stock_room(false);
        assertRefused(publicLot, "REMOTE_NOT_STOCKROOM");
    }

    @Test
    void refusesALotWithoutASystemBlockEvenWhenTheBlockIsOptional() {
        properties.setRequireSystemRemarksBlock(false);
        properties.setNonProdRequireStockRoom(false);
        assertRefused(lot("C", "Human notes only"), "REMOTE_SYSTEM_BLOCK_MISSING");
    }

    @Test
    void refusesALotWhoseSystemBlockNamesAnotherEnvironment() {
        assertRefused(lot("C", systemBlock("dev")), "REMOTE_SYSTEM_BLOCK_ENV_MISMATCH");
    }

    @Test
    void refusesALotOfAnotherListing() {
        assertRefused(lot("C", systemBlock("sandbox").replace("MARKETPLACE_LISTING_ID=100", "MARKETPLACE_LISTING_ID=101")),
                "REMOTE_SYSTEM_BLOCK_LISTING_MISMATCH");
    }

    @Test
    void aLotThatIsAlreadyGoneCompletesWithoutADeleteCall() {
        arrange(mapping(12345));
        when(bricklinkRestClient.getInventories(LOT)).thenThrow(notFound());

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.remoteVerified()).isOne();
        verify(bricklinkRestClient, never()).deleteInventory(anyLong());
        assertListingEnded();
        assertThat(lastRequestUpdate().getSyncRequestStatusCode()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aListingWithoutALotCompletesWithoutCallingBricklink() {
        arrange(mapping(null));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.remoteVerified()).isOne();
        verify(bricklinkRestClient, never()).getInventories(anyLong());
        verify(bricklinkRestClient, never()).deleteInventory(anyLong());
        assertListingEnded();
        assertThat(lastRequestUpdate().getSyncRequestStatusCode()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aLotStillPresentAfterTheDeleteIsRetriedAndTheListingIsKept() {
        arrange(mapping(12345));
        when(bricklinkRestClient.getInventories(LOT)).thenReturn(resource(lot("C", systemBlock("sandbox"))));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.failed()).isOne();
        verify(bricklinkRestClient).deleteInventory(LOT);
        verify(marketplaceListingDao, never()).update(any(MarketplaceListing.class));
        MarketplaceListingSyncRequest retried = lastRequestUpdate();
        assertThat(retried.getSyncRequestStatusCode()).isEqualTo("PENDING");
        assertThat(retried.getLastErrorMessage()).contains("still exists after the delete");
    }

    @Test
    void anotherBricklinkErrorIsRetriedWithoutDeleting() {
        arrange(mapping(12345));
        when(bricklinkRestClient.getInventories(LOT)).thenThrow(new BricklinkClientException(401, "BAD_OAUTH_REQUEST", "TOKEN_IP_MISMATCHED"));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.failed()).isOne();
        verify(bricklinkRestClient, never()).deleteInventory(anyLong());
        assertThat(lastRequestUpdate().getSyncRequestStatusCode()).isEqualTo("PENDING");
    }

    @Test
    void dryRunChecksWithoutDeletingOrWriting() {
        properties.setMode("DRY_RUN");
        arrange(mapping(12345));
        when(bricklinkRestClient.getInventories(LOT)).thenReturn(resource(lot("C", systemBlock("sandbox"))));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.dryRunVerified()).isOne();
        verify(bricklinkRestClient, never()).deleteInventory(anyLong());
        verify(syncRequestDao, never()).update(any());
        verify(marketplaceListingDao, never()).update(any());
    }

    private void assertRefused(Inventory remoteLot, String statusCode) {
        arrange(mapping(12345));
        when(bricklinkRestClient.getInventories(LOT)).thenReturn(resource(remoteLot));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        verify(bricklinkRestClient, never()).deleteInventory(anyLong());
        verify(marketplaceListingDao, never()).update(any(MarketplaceListing.class));
        MarketplaceListingSyncRequest blocked = lastRequestUpdate();
        assertThat(blocked.getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        assertThat(blocked.getLastErrorMessage()).startsWith(statusCode);
    }

    private void arrange(BricklinkMarketplaceListing mapping) {
        when(syncRequestDao.findClaimableByStatusCodeAndSyncRequestTypeCodes(
                eq("PENDING"), eq(Set.of("PRICE_UPDATE", "LISTING_CREATE", "LISTING_DELETE")), any(ZonedDateTime.class), eq(5)))
                .thenReturn(Set.of(request("PENDING")));
        when(syncRequestDao.claim(eq(900L), eq("PENDING"), eq("CLAIMED"), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(request("CLAIMED")));
        when(marketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(MarketplaceListing.builder()
                .marketplaceListingId(100)
                .itemInventoryId(200)
                .listingExternalServiceId(2)
                .listingStatusCode("ACTIVE")
                .externalListingId(mapping.getBricklinkInventoryId() == null ? null : mapping.getBricklinkInventoryId().toString())
                .build()));
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(mapping));
        ItemInventory item = new ItemInventory();
        item.setItemInventoryId(200);
        item.setUuid("inventory-uuid");
        when(itemInventoryDao.findByItemInventoryId(200)).thenReturn(Optional.of(item));
    }

    private MarketplaceListingSyncRequest request(String status) {
        return MarketplaceListingSyncRequest.builder()
                .marketplaceListingSyncRequestId(900L)
                .marketplaceListingId(100)
                .listingExternalServiceId(2)
                .syncRequestTypeCode("LISTING_DELETE")
                .syncRequestStatusCode(status)
                .syncReasonCode("SEED_TEARDOWN")
                .attemptCount("CLAIMED".equals(status) ? 1 : 0)
                .maxAttempts(3)
                .build();
    }

    private BricklinkMarketplaceListing mapping(Integer lotId) {
        return BricklinkMarketplaceListing.builder()
                .marketplaceListingId(100)
                .bricklinkInventoryId(lotId)
                .isStockRoom(true)
                .stockRoomId("C")
                .environmentCode("sandbox")
                .build();
    }

    private Inventory lot(String stockroom, String remarks) {
        Inventory inventory = new Inventory();
        inventory.setInventory_id(LOT);
        inventory.setIs_stock_room(true);
        inventory.setStock_room_id(stockroom);
        inventory.setQuantity(1);
        inventory.setRemarks(remarks);
        return inventory;
    }

    private String systemBlock(String environment) {
        return "[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=" + environment
                + "; MARKETPLACE_LISTING_ID=100; ITEM_INVENTORY_UUID=inventory-uuid [SYSTEM_END]";
    }

    private BricklinkClientException notFound() {
        return new BricklinkClientException(404, "RESOURCE_NOT_FOUND", "Resource not found");
    }

    private BricklinkResource<Inventory> resource(Inventory inventory) {
        BricklinkResource<Inventory> resource = new BricklinkResource<>();
        resource.setData(inventory);
        return resource;
    }

    private void assertListingEnded() {
        ArgumentCaptor<MarketplaceListing> listing = ArgumentCaptor.forClass(MarketplaceListing.class);
        verify(marketplaceListingDao).update(listing.capture());
        assertThat(listing.getValue().getListingStatusCode()).isEqualTo("ENDED");
        assertThat(listing.getValue().getExternalListingId()).isNull();
        ArgumentCaptor<BricklinkMarketplaceListing> mapping = ArgumentCaptor.forClass(BricklinkMarketplaceListing.class);
        verify(bricklinkMarketplaceListingDao).update(mapping.capture());
        assertThat(mapping.getValue().getBricklinkInventoryId()).isNull();
        assertThat(mapping.getValue().getLastRemoteSafetyStatusCode()).isEqualTo("REMOTE_DELETED");
    }

    private MarketplaceListingSyncRequest lastRequestUpdate() {
        ArgumentCaptor<MarketplaceListingSyncRequest> captor = ArgumentCaptor.forClass(MarketplaceListingSyncRequest.class);
        verify(syncRequestDao, org.mockito.Mockito.atLeastOnce()).update(captor.capture());
        return captor.getValue();
    }
}
