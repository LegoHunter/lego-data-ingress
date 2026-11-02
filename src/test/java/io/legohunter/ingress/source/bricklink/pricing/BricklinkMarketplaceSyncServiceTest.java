package io.legohunter.ingress.source.bricklink.pricing;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import com.bricklink.api.rest.model.v1.Item;
import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkMarketplaceSyncServiceTest {
    private final BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();

    @BeforeEach
    void sandboxOwnsStockroomC() {
        // Every test here runs as environment "sandbox", whose own stockroom is C (HOMELAB-20).
        // There is no code default any more, so it must be set explicitly.
        properties.setNonProdStockRoomId("C");
    }

    private final MarketplaceListingSyncRequestDao marketplaceListingSyncRequestDao = mock(MarketplaceListingSyncRequestDao.class);
    private final MarketplaceListingDao marketplaceListingDao = mock(MarketplaceListingDao.class);
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao = mock(BricklinkMarketplaceListingDao.class);
    private final ItemInventoryDao itemInventoryDao = mock(ItemInventoryDao.class);
    private final BricklinkRestClient bricklinkRestClient = mock(BricklinkRestClient.class);
    private final BricklinkMarketplaceSyncService service = new BricklinkMarketplaceSyncService(
            properties,
            marketplaceListingSyncRequestDao,
            marketplaceListingDao,
            bricklinkMarketplaceListingDao,
            itemInventoryDao,
            bricklinkRestClient,
            new BricklinkRemoteInventorySafetyService(),
            new BricklinkListingCreateSafetyService(),
            new BricklinkListingCreateInventoryMapper()
    );

    @Test
    void dryRunVerifiesRemoteInventoryWithoutClaimingOrUpdating() {
        properties.setMode("DRY_RUN");
        properties.setEnvironmentCode("sandbox");
        arrangeRequestAndContext(remoteInventory(systemBlock()));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.mode()).isEqualTo(BricklinkMarketplaceSyncMode.DRY_RUN);
        assertThat(result.dryRunVerified()).isOne();
        verify(marketplaceListingSyncRequestDao, never()).claim(any(), any(), any(), any());
        verify(bricklinkRestClient, never()).updateInventory(any(), any());
        verify(marketplaceListingSyncRequestDao, never()).update(any());
    }

    @Test
    void applyUpdatesRemoteInventoryAndCompletesRequestWhenSafetyPasses() {
        properties.setMode("APPLY");
        properties.setEnvironmentCode("sandbox");
        MarketplaceListingSyncRequest request = request();
        arrangeRequestAndContext(remoteInventory("Human notes " + systemBlock()));
        when(marketplaceListingSyncRequestDao.claim(eq(900L), eq("PENDING"), eq("CLAIMED"), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(claimedRequest()));
        when(marketplaceListingSyncRequestDao.update(any(MarketplaceListingSyncRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(bricklinkMarketplaceListingDao.update(any(BricklinkMarketplaceListing.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.mode()).isEqualTo(BricklinkMarketplaceSyncMode.APPLY);
        assertThat(result.requestsClaimed()).isOne();
        assertThat(result.remoteUpdated()).isOne();
        verify(bricklinkRestClient).updateInventory(eq(12345L), any(Inventory.class));
        verify(marketplaceListingSyncRequestDao).update(any(MarketplaceListingSyncRequest.class));
        verify(bricklinkMarketplaceListingDao).update(any(BricklinkMarketplaceListing.class));
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("PENDING");
    }

    @Test
    void applyBlocksRequestWhenSystemBlockIsMissing() {
        properties.setMode("APPLY");
        properties.setEnvironmentCode("sandbox");
        arrangeRequestAndContext(remoteInventory("Human notes only"));
        when(marketplaceListingSyncRequestDao.claim(eq(900L), eq("PENDING"), eq("CLAIMED"), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(claimedRequest()));
        when(marketplaceListingSyncRequestDao.update(any(MarketplaceListingSyncRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        verify(bricklinkRestClient, never()).updateInventory(any(), any());
        verify(marketplaceListingSyncRequestDao).update(any(MarketplaceListingSyncRequest.class));
    }

    @Test
    void dryRunMapsListingCreateWithoutClaimingOrCallingBricklink() {
        properties.setMode("DRY_RUN");
        properties.setEnvironmentCode("sandbox");
        arrangeListingCreateRequestAndContext();

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.mode()).isEqualTo(BricklinkMarketplaceSyncMode.DRY_RUN);
        assertThat(result.dryRunVerified()).isOne();
        verify(marketplaceListingSyncRequestDao, never()).claim(any(), any(), any(), any());
        verify(bricklinkRestClient, never()).createInventory(any());
        verify(marketplaceListingSyncRequestDao, never()).update(any());
    }

    @Test
    void applyCreatesBricklinkInventoryAndPersistsRemoteIdentityWhenSafetyPasses() {
        properties.setMode("APPLY");
        properties.setEnvironmentCode("sandbox");
        properties.setNonProdStockRoomId("C");
        arrangeListingCreateRequestAndContext();
        when(marketplaceListingSyncRequestDao.claim(eq(901L), eq("PENDING"), eq("CLAIMED"), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(claimedListingCreateRequest()));
        when(bricklinkRestClient.createInventory(any(Inventory.class))).thenReturn(resource(createdInventory()));
        when(bricklinkRestClient.getInventories(45678L)).thenReturn(resource(createdInventory()));
        when(marketplaceListingDao.update(any(MarketplaceListing.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(bricklinkMarketplaceListingDao.update(any(BricklinkMarketplaceListing.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketplaceListingSyncRequestDao.update(any(MarketplaceListingSyncRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.remoteUpdated()).isOne();
        ArgumentCaptor<Inventory> inventoryCaptor = ArgumentCaptor.forClass(Inventory.class);
        verify(bricklinkRestClient).createInventory(inventoryCaptor.capture());
        assertThat(inventoryCaptor.getValue().getItem().getNo()).isEqualTo("6390-1");
        assertThat(inventoryCaptor.getValue().getItem().getType()).isEqualTo("SET");
        assertThat(inventoryCaptor.getValue().getIs_stock_room()).isTrue();
        assertThat(inventoryCaptor.getValue().getStock_room_id()).isEqualTo("C");
        verify(marketplaceListingDao).update(any(MarketplaceListing.class));
        verify(bricklinkMarketplaceListingDao).update(any(BricklinkMarketplaceListing.class));
        verify(marketplaceListingSyncRequestDao).update(any(MarketplaceListingSyncRequest.class));
    }

    private void arrangeRequestAndContext(Inventory remoteInventory) {
        when(marketplaceListingSyncRequestDao.findClaimableByStatusCodeAndSyncRequestTypeCodes(
                eq("PENDING"),
                eq(Set.of("PRICE_UPDATE", "LISTING_CREATE")),
                any(ZonedDateTime.class),
                eq(5)
        ))
                .thenReturn(Set.of(request()));
        when(marketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(listing()));
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(bricklinkListing()));
        when(itemInventoryDao.findByItemInventoryId(200)).thenReturn(Optional.of(itemInventory()));
        when(bricklinkRestClient.getInventories(12345L)).thenReturn(resource(remoteInventory));
    }

    private MarketplaceListingSyncRequest request() {
        return MarketplaceListingSyncRequest.builder()
                .marketplaceListingSyncRequestId(900L)
                .marketplaceListingId(100)
                .listingExternalServiceId(2)
                .pricingDecisionId(500L)
                .syncRequestTypeCode("PRICE_UPDATE")
                .syncRequestStatusCode("PENDING")
                .requestedUnitPrice(new BigDecimal("219.00"))
                .currencyCode("USD")
                .remoteInventoryId("12345")
                .attemptCount(0)
                .maxAttempts(3)
                .build();
    }

    private void arrangeListingCreateRequestAndContext() {
        when(marketplaceListingSyncRequestDao.findClaimableByStatusCodeAndSyncRequestTypeCodes(
                eq("PENDING"),
                eq(Set.of("PRICE_UPDATE", "LISTING_CREATE")),
                any(ZonedDateTime.class),
                eq(5)
        ))
                .thenReturn(Set.of(listingCreateRequest()));
        when(marketplaceListingDao.findByMarketplaceListingId(101)).thenReturn(Optional.of(listingCreateListing()));
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(101)).thenReturn(Optional.of(bricklinkDraftListing()));
        when(itemInventoryDao.findByItemInventoryId(201)).thenReturn(Optional.of(sellableItemInventory()));
    }

    private MarketplaceListingSyncRequest claimedRequest() {
        MarketplaceListingSyncRequest request = request();
        request.setSyncRequestStatusCode("CLAIMED");
        request.setAttemptCount(1);
        return request;
    }

    private MarketplaceListing listing() {
        return MarketplaceListing.builder()
                .marketplaceListingId(100)
                .itemInventoryId(200)
                .listingExternalServiceId(2)
                .listingStatusCode("ACTIVE")
                .build();
    }

    private MarketplaceListing listingCreateListing() {
        return MarketplaceListing.builder()
                .marketplaceListingId(101)
                .itemInventoryId(201)
                .listingExternalServiceId(2)
                .externalCatalogItemId(301)
                .listingStatusCode("DRAFT")
                .title("Main Street")
                .description("Complete used set")
                .unitPrice(new BigDecimal("219.00"))
                .currencyCode("USD")
                .externalCatalogItem(ExternalCatalogItem.builder()
                        .externalCatalogItemId(301)
                        .externalServiceId(2)
                        .externalItemKey("6390-1")
                        .itemName("Main Street")
                        .itemTypeCode("S")
                        .build())
                .build();
    }

    private BricklinkMarketplaceListing bricklinkListing() {
        return BricklinkMarketplaceListing.builder()
                .marketplaceListingId(100)
                .bricklinkInventoryId(12345)
                .build();
    }

    private BricklinkMarketplaceListing bricklinkDraftListing() {
        return BricklinkMarketplaceListing.builder()
                .marketplaceListingId(101)
                .isStockRoom(true)
                .stockRoomId("C")
                .remarks("Human notes")
                .build();
    }

    private ItemInventory itemInventory() {
        ItemInventory itemInventory = new ItemInventory();
        itemInventory.setItemInventoryId(200);
        itemInventory.setUuid("inventory-uuid");
        return itemInventory;
    }

    private ItemInventory sellableItemInventory() {
        ItemInventory itemInventory = new ItemInventory();
        itemInventory.setItemInventoryId(201);
        itemInventory.setUuid("listing-create-uuid");
        itemInventory.setActive(true);
        itemInventory.setSaleIntentCode("SELLABLE");
        itemInventory.setInventoryStateCode("AVAILABLE");
        itemInventory.setNewOrUsed("U");
        itemInventory.setCompleteness("C");
        itemInventory.setSealed(false);
        return itemInventory;
    }

    private Inventory remoteInventory(String remarks) {
        Inventory inventory = new Inventory();
        inventory.setInventory_id(12345L);
        inventory.setIs_stock_room(true);
        inventory.setStock_room_id("C");
        inventory.setRemarks(remarks);
        return inventory;
    }

    private BricklinkResource<Inventory> resource(Inventory inventory) {
        BricklinkResource<Inventory> resource = new BricklinkResource<>();
        resource.setData(inventory);
        return resource;
    }

    private MarketplaceListingSyncRequest listingCreateRequest() {
        return MarketplaceListingSyncRequest.builder()
                .marketplaceListingSyncRequestId(901L)
                .marketplaceListingId(101)
                .listingExternalServiceId(2)
                .pricingDecisionId(501L)
                .syncRequestTypeCode("LISTING_CREATE")
                .syncRequestStatusCode("PENDING")
                .requestedUnitPrice(new BigDecimal("219.00"))
                .currencyCode("USD")
                .remoteVisibilityScopeCode("STOCKROOM")
                .remoteVisibilityContainerId("C")
                .remoteIsPubliclyAvailable(false)
                .attemptCount(0)
                .maxAttempts(3)
                .build();
    }

    private MarketplaceListingSyncRequest claimedListingCreateRequest() {
        MarketplaceListingSyncRequest request = listingCreateRequest();
        request.setSyncRequestStatusCode("CLAIMED");
        request.setAttemptCount(1);
        return request;
    }

    private Inventory createdInventory() {
        Inventory inventory = new Inventory();
        inventory.setInventory_id(45678L);
        inventory.setItem(createdItem());
        inventory.setQuantity(1);
        inventory.setIs_stock_room(true);
        inventory.setStock_room_id("C");
        inventory.setRemarks("[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=sandbox; MARKETPLACE_LISTING_ID=101; ITEM_INVENTORY_UUID=listing-create-uuid [SYSTEM_END]");
        return inventory;
    }

    private Item createdItem() {
        Item item = new Item();
        item.setNo("6390-1");
        item.setType("SET");
        return item;
    }

    private String systemBlock() {
        return "[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=sandbox; MARKETPLACE_LISTING_ID=100; ITEM_INVENTORY_UUID=inventory-uuid [SYSTEM_END]";
    }
}
