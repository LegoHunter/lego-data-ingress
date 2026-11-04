package io.legohunter.ingress.source.bricklink.pricing;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import com.bricklink.api.rest.model.v1.Item;
import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.data.dto.PricingDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private final PricingDecisionDao pricingDecisionDao = mock(PricingDecisionDao.class);
    private final CountingTransactions transactions = new CountingTransactions();
    private final BricklinkMarketplaceSyncService service = new BricklinkMarketplaceSyncService(
            properties,
            marketplaceListingSyncRequestDao,
            marketplaceListingDao,
            bricklinkMarketplaceListingDao,
            itemInventoryDao,
            bricklinkRestClient,
            new BricklinkRemoteInventorySafetyService(),
            new BricklinkListingCreateSafetyService(),
            new BricklinkListingCreateInventoryMapper(),
            pricingDecisionDao,
            transactions
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
    void priceUpdateChangesTheLocalPriceOnlyAfterBricklinkConfirmsIt() {
        applyPriceUpdateAsSandbox();
        Inventory before = remoteInventory("Human notes " + systemBlock());
        before.setUnit_price(225.00);
        arrangeRequestAndContext(before);
        arrangeReadBack(before, after -> { });
        PricingDecision applied = PricingDecision.builder().pricingDecisionId(500L).decisionStatusCode("APPLIED").build();
        when(pricingDecisionDao.transitionStatus(eq(500L), eq("ACCEPTED"), eq("APPLIED"), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(applied));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.mode()).isEqualTo(BricklinkMarketplaceSyncMode.APPLY);
        assertThat(result.requestsClaimed()).isOne();
        assertThat(result.remoteUpdated()).isOne();
        assertThat(result.remoteVerified()).isOne();
        assertThat(result.blocked()).isZero();
        assertThat(transactions.executions).isOne();

        // BrickLink first, then the read-back, then the local price and the decision.
        InOrder order = inOrder(bricklinkRestClient, marketplaceListingDao, pricingDecisionDao, marketplaceListingSyncRequestDao);
        order.verify(bricklinkRestClient).updateInventory(eq(12345L), any(Inventory.class));
        // The read-back: one read before the update (the safety check), one after it.
        order.verify(bricklinkRestClient).getInventories(12345L);
        ArgumentCaptor<MarketplaceListing> listingCaptor = ArgumentCaptor.forClass(MarketplaceListing.class);
        order.verify(marketplaceListingDao).update(listingCaptor.capture());
        order.verify(pricingDecisionDao).transitionStatus(eq(500L), eq("ACCEPTED"), eq("APPLIED"), any(ZonedDateTime.class));
        assertThat(listingCaptor.getValue().getUnitPrice()).isEqualByComparingTo("219.00");
        assertThat(listingCaptor.getValue().getLastSynchronizedAt()).isNotNull();

        MarketplaceListingSyncRequest finished = lastRequestUpdate();
        assertThat(finished.getSyncRequestStatusCode()).isEqualTo("SUCCEEDED");
        assertThat(finished.getAppliedLocalAt()).isNotNull();
        assertThat(finished.getCompletedAt()).isNotNull();
        // Visibility is what the read-back shows: a stockroom C lot, not publicly available.
        assertThat(finished.getRemoteVisibilityScopeCode()).isEqualTo("STOCKROOM");
        assertThat(finished.getRemoteVisibilityContainerId()).isEqualTo("C");
        assertThat(finished.getRemoteIsPubliclyAvailable()).isFalse();
        assertThat(lastBricklinkListingUpdate().getLastRemoteSafetyStatusCode()).isEqualTo("VERIFIED");
        // A verified lot is marked as this environment's.
        assertThat(lastBricklinkListingUpdate().getEnvironmentCode()).isEqualTo("sandbox");
        assertThat(lastBricklinkListingUpdate().getSystemRemarksHash()).isNotBlank();
    }

    @Test
    void priceUpdateKeepsTheLocalPriceWhenTheReadBackShowsAnotherPrice() {
        applyPriceUpdateAsSandbox();
        arrangeRequestAndContext(remoteInventory("Human notes " + systemBlock()));
        arrangeReadBack(remoteInventory("Human notes " + systemBlock()), after -> after.setUnit_price(225.00));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertBlockedWithoutLocalChange(result, "REMOTE_PRICE_MISMATCH");
        assertThat(lastRequestUpdate().getLastErrorMessage()).contains("225.00").contains("219.00");
    }

    @Test
    void priceUpdateKeepsTheLocalPriceWhenTheLotLeftTheEnvironmentsStockroom() {
        applyPriceUpdateAsSandbox();
        arrangeRequestAndContext(remoteInventory("Human notes " + systemBlock()));
        arrangeReadBack(remoteInventory("Human notes " + systemBlock()), after -> {
            after.setIs_stock_room(false);
            after.setStock_room_id(null);
        });

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertBlockedWithoutLocalChange(result, "REMOTE_STOCKROOM_MISMATCH");
        assertThat(lastBricklinkListingUpdate().getIsStockRoom()).isFalse();
    }

    @Test
    void priceUpdateKeepsTheLocalPriceWhenTheReadBackRemarksDiffer() {
        applyPriceUpdateAsSandbox();
        arrangeRequestAndContext(remoteInventory("Human notes " + systemBlock()));
        arrangeReadBack(remoteInventory("Human notes " + systemBlock()), after -> after.setRemarks("Edited by hand"));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertBlockedWithoutLocalChange(result, "REMOTE_REMARKS_MISMATCH");
    }

    @Test
    void priceUpdateKeepsTheLocalPriceWhenTheReadBackReturnsNoLot() {
        applyPriceUpdateAsSandbox();
        Inventory before = remoteInventory("Human notes " + systemBlock());
        arrangeRequestAndContext(before);
        when(bricklinkRestClient.getInventories(12345L)).thenReturn(resource(before), resource(null));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertBlockedWithoutLocalChange(result, "REMOTE_INVENTORY_MISSING");
    }

    @Test
    void priceUpdateThatFailsOnBricklinkIsRetriedAndLeavesTheLocalPriceAlone() {
        applyPriceUpdateAsSandbox();
        arrangeRequestAndContext(remoteInventory("Human notes " + systemBlock()));
        when(bricklinkRestClient.updateInventory(eq(12345L), any(Inventory.class)))
                .thenThrow(new IllegalStateException("BrickLink returned 500"));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.failed()).isOne();
        assertThat(result.remoteVerified()).isZero();
        MarketplaceListingSyncRequest retried = lastRequestUpdate();
        assertThat(retried.getSyncRequestStatusCode()).isEqualTo("PENDING");
        assertThat(retried.getLastErrorMessage()).contains("BrickLink returned 500");
        verify(marketplaceListingDao, never()).update(any());
        verify(marketplaceListingDao, never()).updateUnitPrice(any(), any(), any());
        verify(pricingDecisionDao, never()).transitionStatus(any(), any(), any(), any());
    }

    @Test
    void priceUpdateIsBlockedBeforeAnyWriteWhenTheSystemBlockIsMissing() {
        applyPriceUpdateAsSandbox();
        arrangeRequestAndContext(remoteInventory("Human notes only"));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        verify(bricklinkRestClient, never()).updateInventory(any(), any());
        assertThat(lastRequestUpdate().getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        verify(marketplaceListingDao, never()).update(any());
        verify(pricingDecisionDao, never()).transitionStatus(any(), any(), any(), any());
    }

    @Test
    void aBlockedPriceUpdateRecordsTheReasonButDoesNotMarkTheLotAsThisEnvironments() {
        // A row this environment never verified, such as one copied in from another environment.
        applyPriceUpdateAsSandbox();
        arrangeRequestAndContext(remoteInventory("Human notes only"));
        BricklinkMarketplaceListing mapping = bricklinkListing();
        mapping.setEnvironmentCode("prod");
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(mapping));

        service.runOnce();

        BricklinkMarketplaceListing recorded = lastBricklinkListingUpdate();
        assertThat(recorded.getLastRemoteSafetyStatusCode()).isEqualTo("REMOTE_SYSTEM_BLOCK_MISSING");
        assertThat(recorded.getLastRemoteSafetyMessage()).isNotBlank();
        assertThat(recorded.getLastRemoteVerifiedAt()).isNotNull();
        assertThat(recorded.getEnvironmentCode()).isEqualTo("prod");
        assertThat(recorded.getSystemRemarksHash()).isNull();
    }

    @Test
    void aBlockedPriceUpdateLeavesAnEmptyEnvironmentEmpty() {
        applyPriceUpdateAsSandbox();
        arrangeRequestAndContext(remoteInventory("Human notes only"));

        service.runOnce();

        assertThat(lastBricklinkListingUpdate().getEnvironmentCode()).isNull();
    }

    @Test
    void priceUpdateStillSucceedsWhenTheDecisionWasNotAccepted() {
        // A request queued by the older local-first code: its decision is PROPOSED with applied_at set.
        applyPriceUpdateAsSandbox();
        Inventory before = remoteInventory("Human notes " + systemBlock());
        arrangeRequestAndContext(before);
        arrangeReadBack(before, after -> { });
        when(pricingDecisionDao.transitionStatus(eq(500L), eq("ACCEPTED"), eq("APPLIED"), any(ZonedDateTime.class)))
                .thenReturn(Optional.empty());

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.remoteVerified()).isOne();
        assertThat(lastRequestUpdate().getSyncRequestStatusCode()).isEqualTo("SUCCEEDED");
        verify(marketplaceListingDao).update(any(MarketplaceListing.class));
    }

    @Test
    void priceUpdateWithoutAPricingDecisionDoesNotTouchDecisions() {
        applyPriceUpdateAsSandbox();
        MarketplaceListingSyncRequest manual = request();
        manual.setPricingDecisionId(null);
        Inventory before = remoteInventory("Human notes " + systemBlock());
        arrangeRequestAndContext(before);
        when(marketplaceListingSyncRequestDao.findClaimableByStatusCodeAndSyncRequestTypeCodes(
                eq("PENDING"), eq(Set.of("PRICE_UPDATE", "LISTING_CREATE")), any(ZonedDateTime.class), eq(5)))
                .thenReturn(Set.of(manual));
        MarketplaceListingSyncRequest claimedManual = claimedRequest();
        claimedManual.setPricingDecisionId(null);
        when(marketplaceListingSyncRequestDao.claim(eq(900L), eq("PENDING"), eq("CLAIMED"), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(claimedManual));
        arrangeReadBack(before, after -> { });

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.remoteVerified()).isOne();
        verify(pricingDecisionDao, never()).transitionStatus(any(), any(), any(), any());
        verify(marketplaceListingDao).update(any(MarketplaceListing.class));
    }

    private void applyPriceUpdateAsSandbox() {
        properties.setMode("APPLY");
        properties.setEnvironmentCode("sandbox");
        when(marketplaceListingSyncRequestDao.claim(eq(900L), eq("PENDING"), eq("CLAIMED"), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(claimedRequest()));
        when(marketplaceListingSyncRequestDao.update(any(MarketplaceListingSyncRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(bricklinkMarketplaceListingDao.update(any(BricklinkMarketplaceListing.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(marketplaceListingDao.update(any(MarketplaceListing.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * The first read returns {@code before}. After {@code updateInventory}, reads return the lot as
     * BrickLink would hold it with the update applied, changed further by {@code tamper}.
     */
    private void arrangeReadBack(Inventory before, java.util.function.Consumer<Inventory> tamper) {
        Inventory[] written = new Inventory[1];
        when(bricklinkRestClient.updateInventory(eq(12345L), any(Inventory.class))).thenAnswer(invocation -> {
            written[0] = invocation.getArgument(1);
            return null;
        });
        when(bricklinkRestClient.getInventories(12345L)).thenAnswer(invocation -> {
            if (written[0] == null) {
                return resource(before);
            }
            Inventory after = remoteInventory(written[0].getRemarks() == null ? before.getRemarks() : written[0].getRemarks());
            after.setUnit_price(written[0].getUnit_price());
            after.setIs_stock_room(written[0].getIs_stock_room() == null ? before.getIs_stock_room() : written[0].getIs_stock_room());
            after.setStock_room_id(written[0].getStock_room_id() == null ? before.getStock_room_id() : written[0].getStock_room_id());
            tamper.accept(after);
            return resource(after);
        });
    }

    private void assertBlockedWithoutLocalChange(BricklinkMarketplaceSyncResult result, String statusCode) {
        assertThat(result.blocked()).isOne();
        assertThat(result.remoteVerified()).isZero();
        verify(bricklinkRestClient).updateInventory(eq(12345L), any(Inventory.class));
        MarketplaceListingSyncRequest blocked = lastRequestUpdate();
        assertThat(blocked.getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        assertThat(blocked.getLastErrorMessage()).startsWith(statusCode);
        assertThat(blocked.getAppliedLocalAt()).isNull();
        assertThat(lastBricklinkListingUpdate().getLastRemoteSafetyStatusCode()).isEqualTo(statusCode);
        assertThat(lastBricklinkListingUpdate().getEnvironmentCode()).isNull();
        verify(marketplaceListingDao, never()).update(any());
        verify(marketplaceListingDao, never()).updateUnitPrice(any(), any(), any());
        verify(pricingDecisionDao, never()).transitionStatus(any(), any(), any(), any());
    }

    private MarketplaceListingSyncRequest lastRequestUpdate() {
        ArgumentCaptor<MarketplaceListingSyncRequest> captor = ArgumentCaptor.forClass(MarketplaceListingSyncRequest.class);
        verify(marketplaceListingSyncRequestDao, org.mockito.Mockito.atLeastOnce()).update(captor.capture());
        return captor.getValue();
    }

    private BricklinkMarketplaceListing lastBricklinkListingUpdate() {
        ArgumentCaptor<BricklinkMarketplaceListing> captor = ArgumentCaptor.forClass(BricklinkMarketplaceListing.class);
        verify(bricklinkMarketplaceListingDao, org.mockito.Mockito.atLeastOnce()).update(captor.capture());
        return captor.getValue();
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
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(lots());
        when(bricklinkRestClient.createInventory(any(Inventory.class))).thenReturn(resource(createdInventory()));
        when(bricklinkRestClient.getInventories(45678L)).thenReturn(resource(createdInventory()));
        stubUpdates();

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.remoteUpdated()).isOne();
        ArgumentCaptor<Inventory> inventoryCaptor = ArgumentCaptor.forClass(Inventory.class);
        verify(bricklinkRestClient).createInventory(inventoryCaptor.capture());
        assertThat(inventoryCaptor.getValue().getItem().getNo()).isEqualTo("6390-1");
        assertThat(inventoryCaptor.getValue().getItem().getType()).isEqualTo("SET");
        assertThat(inventoryCaptor.getValue().getIs_stock_room()).isTrue();
        assertThat(inventoryCaptor.getValue().getStock_room_id()).isEqualTo("C");
        verify(marketplaceListingDao).update(any(MarketplaceListing.class));
        // Once to save the new id right after the create, once for the final state.
        verify(bricklinkMarketplaceListingDao, times(2)).update(any(BricklinkMarketplaceListing.class));
        verify(marketplaceListingSyncRequestDao, times(2)).update(any(MarketplaceListingSyncRequest.class));
    }

    @Test
    void savesTheNewLotsIdBeforeReadingItBack() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        arrangeListingCreate(request, bricklinkDraftListing());
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(lots());
        when(bricklinkRestClient.createInventory(any(Inventory.class))).thenReturn(resource(createdInventory()));
        when(bricklinkRestClient.getInventories(45678L)).thenReturn(resource(createdInventory()));
        stubUpdates();

        service.runOnce();

        InOrder order = inOrder(bricklinkRestClient, marketplaceListingSyncRequestDao, bricklinkMarketplaceListingDao);
        order.verify(bricklinkRestClient).createInventory(any(Inventory.class));
        order.verify(marketplaceListingSyncRequestDao).update(request);
        order.verify(bricklinkMarketplaceListingDao).update(any(BricklinkMarketplaceListing.class));
        order.verify(bricklinkRestClient).getInventories(45678L);
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aReadBackFailureFollowedByARetryCreatesTheLotOnlyOnce() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        BricklinkMarketplaceListing mapping = bricklinkDraftListing();
        arrangeListingCreate(request, mapping);
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(lots());
        when(bricklinkRestClient.createInventory(any(Inventory.class))).thenReturn(resource(createdInventory()));
        when(bricklinkRestClient.getInventories(45678L))
                .thenThrow(new RuntimeException("Read timed out"))
                .thenReturn(resource(createdInventory()));
        stubUpdates();

        BricklinkMarketplaceSyncResult first = service.runOnce();

        assertThat(first.failed()).isOne();
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("PENDING");
        assertThat(request.getRemoteInventoryId()).isEqualTo("45678");
        assertThat(mapping.getBricklinkInventoryId()).isEqualTo(45678);

        BricklinkMarketplaceSyncResult retry = service.runOnce();

        assertThat(retry.remoteUpdated()).isOne();
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("SUCCEEDED");
        verify(bricklinkRestClient, times(1)).createInventory(any(Inventory.class));
        // The retry resumes from the saved id, so it does not search the store again.
        verify(bricklinkRestClient, times(1)).getInventories(anyMap());
    }

    @Test
    void aLostCreateResponseFollowedByARetryTakesOverTheLotInsteadOfCreatingAnother() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        arrangeListingCreate(request, bricklinkDraftListing());
        // BrickLink created the lot, but the response never arrived.
        when(bricklinkRestClient.getInventories(anyMap()))
                .thenReturn(lots())
                .thenReturn(lots(createdInventory()));
        when(bricklinkRestClient.createInventory(any(Inventory.class))).thenThrow(new RuntimeException("Connection reset"));
        when(bricklinkRestClient.getInventories(45678L)).thenReturn(resource(createdInventory()));
        stubUpdates();

        BricklinkMarketplaceSyncResult first = service.runOnce();
        assertThat(first.failed()).isOne();
        assertThat(request.getRemoteInventoryId()).isNull();

        BricklinkMarketplaceSyncResult retry = service.runOnce();

        assertThat(retry.remoteUpdated()).isOne();
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("SUCCEEDED");
        assertThat(request.getRemoteInventoryId()).isEqualTo("45678");
        verify(bricklinkRestClient, times(1)).createInventory(any(Inventory.class));
    }

    @Test
    void refusesToCreateWhenTheStoreAlreadyHasSeveralLotsForTheListing() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        arrangeListingCreate(request, bricklinkDraftListing());
        Inventory second = createdInventory();
        second.setInventory_id(45679L);
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(lots(createdInventory(), second));
        stubUpdates();

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        assertThat(request.getLastErrorMessage())
                .startsWith("DUPLICATE_REMOTE_INVENTORY: ")
                .contains("[45678, 45679]");
        verify(bricklinkRestClient, never()).createInventory(any());
    }

    @Test
    void createsNothingWhenTheStoreSearchFails() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        arrangeListingCreate(request, bricklinkDraftListing());
        when(bricklinkRestClient.getInventories(anyMap())).thenThrow(new RuntimeException("TOKEN_IP_MISMATCHED"));
        stubUpdates();

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.failed()).isOne();
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("PENDING");
        verify(bricklinkRestClient, never()).createInventory(any());
    }

    @Test
    void createsNothingWhenTheStoreSearchReturnsNoData() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        arrangeListingCreate(request, bricklinkDraftListing());
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(new BricklinkResource<>());
        stubUpdates();

        assertThat(service.runOnce().failed()).isOne();
        verify(bricklinkRestClient, never()).createInventory(any());
    }

    @Test
    void ignoresLotsOwnedByOtherListingsOrEnvironments() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        arrangeListingCreate(request, bricklinkDraftListing());
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(lots(
                lot(1L, "[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=sandbox; MARKETPLACE_LISTING_ID=999; ITEM_INVENTORY_UUID=listing-create-uuid [SYSTEM_END]"),
                lot(2L, "[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=dev; MARKETPLACE_LISTING_ID=101; ITEM_INVENTORY_UUID=listing-create-uuid [SYSTEM_END]"),
                lot(3L, "[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=sandbox; MARKETPLACE_LISTING_ID=101; ITEM_INVENTORY_UUID=another-uuid [SYSTEM_END]"),
                lot(4L, "Human notes only"),
                lot(5L, null)));
        when(bricklinkRestClient.createInventory(any(Inventory.class))).thenReturn(resource(createdInventory()));
        when(bricklinkRestClient.getInventories(45678L)).thenReturn(resource(createdInventory()));
        stubUpdates();

        assertThat(service.runOnce().remoteUpdated()).isOne();
        verify(bricklinkRestClient, times(1)).createInventory(any(Inventory.class));
    }

    @Test
    void stillRefusesAListingThatAlreadyHadALotBeforeThisRequest() {
        // The mapping has a lot, but this request never saved one: not ours to resume or hide.
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        BricklinkMarketplaceListing mapping = bricklinkDraftListing();
        mapping.setBricklinkInventoryId(11111);
        arrangeListingCreate(request, mapping);
        stubUpdates();

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        assertThat(request.getLastErrorMessage()).startsWith("REMOTE_INVENTORY_ALREADY_EXISTS");
        verify(bricklinkRestClient, never()).getInventories(anyMap());
        verify(bricklinkRestClient, never()).getInventories(anyLong());
        verify(bricklinkRestClient, never()).createInventory(any());
        verify(bricklinkRestClient, never()).updateInventory(any(), any());
    }

    @Test
    void blocksACreateThatBricklinkConsolidatedIntoALotWithAnotherQuantity() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        arrangeListingCreate(request, bricklinkDraftListing());
        // BrickLink merged the new lot into a matching one instead of creating a lot of 1.
        Inventory consolidated = createdInventory();
        consolidated.setQuantity(2);
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(lots());
        when(bricklinkRestClient.createInventory(any(Inventory.class))).thenReturn(resource(consolidated));
        when(bricklinkRestClient.getInventories(45678L)).thenReturn(resource(consolidated));
        stubUpdates();

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        assertThat(result.remoteUpdated()).isZero();
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        assertThat(request.getLastErrorMessage())
                .startsWith("REMOTE_QUANTITY_MISMATCH: BrickLink lot holds quantity 2 but this listing put up 1")
                .endsWith("; lot 45678 is already hidden in stockroom C");
    }

    @Test
    void blocksTakingOverAnOwnedLotWithAnotherQuantity() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        arrangeListingCreate(request, bricklinkDraftListing());
        Inventory owned = createdInventory();
        owned.setQuantity(2);
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(lots(owned));
        when(bricklinkRestClient.getInventories(45678L)).thenReturn(resource(owned));
        stubUpdates();

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        assertThat(request.getLastErrorMessage()).startsWith("REMOTE_QUANTITY_MISMATCH: ");
        verify(bricklinkRestClient, never()).createInventory(any(Inventory.class));
    }

    @Test
    void hidesANewLotThatFailsThePostCreateCheckInTheEnvironmentsStockroom() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        arrangeListingCreate(request, bricklinkDraftListing());
        Inventory publicLot = createdInventory();
        publicLot.setIs_stock_room(false);
        publicLot.setStock_room_id(null);
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(lots());
        when(bricklinkRestClient.createInventory(any(Inventory.class))).thenReturn(resource(publicLot));
        when(bricklinkRestClient.getInventories(45678L)).thenReturn(resource(publicLot));
        stubUpdates();

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        ArgumentCaptor<Inventory> hide = ArgumentCaptor.forClass(Inventory.class);
        verify(bricklinkRestClient).updateInventory(eq(45678L), hide.capture());
        assertThat(hide.getValue().getIs_stock_room()).isTrue();
        assertThat(hide.getValue().getStock_room_id()).isEqualTo("C");
        assertThat(request.getLastErrorMessage())
                .startsWith("REMOTE_NOT_STOCKROOM: ")
                .endsWith("; lot 45678 hidden in stockroom C");
    }

    @Test
    void aNewLotThatFailsThePostCreateCheckIsNotMarkedAsThisEnvironments() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        BricklinkMarketplaceListing draft = bricklinkDraftListing();
        draft.setEnvironmentCode(null);
        arrangeListingCreate(request, draft);
        Inventory publicLot = createdInventory();
        publicLot.setIs_stock_room(false);
        publicLot.setStock_room_id(null);
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(lots());
        when(bricklinkRestClient.createInventory(any(Inventory.class))).thenReturn(resource(publicLot));
        when(bricklinkRestClient.getInventories(45678L)).thenReturn(resource(publicLot));
        stubUpdates();

        service.runOnce();

        BricklinkMarketplaceListing recorded = lastBricklinkListingUpdate();
        assertThat(recorded.getLastRemoteSafetyStatusCode()).isEqualTo("REMOTE_NOT_STOCKROOM");
        assertThat(recorded.getEnvironmentCode()).isNull();
        assertThat(recorded.getSystemRemarksHash()).isNull();
    }

    @Test
    void aBlockedCreateRecordsTheReasonAndKeepsTheDraftAsItWas() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        BricklinkMarketplaceListing mapping = bricklinkDraftListing();
        mapping.setBricklinkInventoryId(11111);
        mapping.setEnvironmentCode("prod");
        arrangeListingCreate(request, mapping);
        stubUpdates();

        service.runOnce();

        BricklinkMarketplaceListing recorded = lastBricklinkListingUpdate();
        assertThat(recorded.getLastRemoteSafetyStatusCode()).isEqualTo("REMOTE_INVENTORY_ALREADY_EXISTS");
        assertThat(recorded.getLastRemoteSafetyMessage()).isNotBlank();
        assertThat(recorded.getLastRemoteVerifiedAt()).isNotNull();
        // Not claimed for this environment, and the draft's own remarks and stockroom survive.
        assertThat(recorded.getEnvironmentCode()).isEqualTo("prod");
        assertThat(recorded.getRemarks()).isEqualTo("Human notes");
        assertThat(recorded.getIsStockRoom()).isTrue();
        assertThat(recorded.getStockRoomId()).isEqualTo("C");
    }

    @Test
    void aCreateThatPassesMarksTheLotAsThisEnvironments() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        BricklinkMarketplaceListing draft = bricklinkDraftListing();
        draft.setEnvironmentCode(null);
        arrangeListingCreate(request, draft);
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(lots());
        when(bricklinkRestClient.createInventory(any(Inventory.class))).thenReturn(resource(createdInventory()));
        when(bricklinkRestClient.getInventories(45678L)).thenReturn(resource(createdInventory()));
        stubUpdates();

        service.runOnce();

        BricklinkMarketplaceListing recorded = lastBricklinkListingUpdate();
        assertThat(recorded.getLastRemoteSafetyStatusCode()).isEqualTo("VERIFIED");
        assertThat(recorded.getEnvironmentCode()).isEqualTo("sandbox");
        assertThat(recorded.getSystemRemarksHash()).isNotBlank();
    }

    @Test
    void prodHidesANewLotThatFailsThePostCreateCheckInStockroomA() {
        properties.setMode("APPLY");
        properties.setEnvironmentCode("prod");
        properties.setProduction(true);
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        request.setRemoteVisibilityScopeCode("PUBLIC");
        request.setRemoteVisibilityContainerId(null);
        request.setRemoteIsPubliclyAvailable(true);
        BricklinkMarketplaceListing mapping = bricklinkDraftListing();
        mapping.setIsStockRoom(false);
        mapping.setStockRoomId(null);
        arrangeListingCreate(request, mapping);
        // BrickLink kept the lot public but dropped our system remarks block.
        Inventory unmanagedLot = createdInventory();
        unmanagedLot.setIs_stock_room(false);
        unmanagedLot.setStock_room_id(null);
        unmanagedLot.setRemarks("Human notes");
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(lots());
        when(bricklinkRestClient.createInventory(any(Inventory.class))).thenReturn(resource(unmanagedLot));
        when(bricklinkRestClient.getInventories(45678L)).thenReturn(resource(unmanagedLot));
        stubUpdates();

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        ArgumentCaptor<Inventory> hide = ArgumentCaptor.forClass(Inventory.class);
        verify(bricklinkRestClient).updateInventory(eq(45678L), hide.capture());
        assertThat(hide.getValue().getIs_stock_room()).isTrue();
        assertThat(hide.getValue().getStock_room_id()).isEqualTo("A");
        assertThat(request.getLastErrorMessage())
                .startsWith("REMOTE_SYSTEM_BLOCK_MISSING: ")
                .endsWith("; lot 45678 hidden in stockroom A");
    }

    @Test
    void prodRecordsAStockroomLotAsNotPubliclyAvailable() {
        properties.setMode("APPLY");
        properties.setEnvironmentCode("prod");
        properties.setProduction(true);
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        request.setRemoteVisibilityScopeCode("STOCKROOM");
        request.setRemoteVisibilityContainerId("A");
        BricklinkMarketplaceListing mapping = bricklinkDraftListing();
        mapping.setStockRoomId("A");
        arrangeListingCreate(request, mapping);
        Inventory stockroomLot = createdInventory();
        stockroomLot.setStock_room_id("A");
        stockroomLot.setRemarks(stockroomLot.getRemarks().replace("LEGOHUNTER_ENV=sandbox", "LEGOHUNTER_ENV=prod"));
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(lots());
        when(bricklinkRestClient.createInventory(any(Inventory.class))).thenReturn(resource(stockroomLot));
        when(bricklinkRestClient.getInventories(45678L)).thenReturn(resource(stockroomLot));
        stubUpdates();

        BricklinkMarketplaceSyncResult result = service.runOnce();

        // PROD used to record every succeeded lot as public; a stockroom A lot is not.
        assertThat(result.blocked()).isZero();
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("SUCCEEDED");
        assertThat(request.getRemoteVisibilityScopeCode()).isEqualTo("STOCKROOM");
        assertThat(request.getRemoteVisibilityContainerId()).isEqualTo("A");
        assertThat(request.getRemoteIsPubliclyAvailable()).isFalse();
    }

    @Test
    void reportsWhenHidingABlockedLotFails() {
        applyAsSandbox();
        MarketplaceListingSyncRequest request = claimedListingCreateRequest();
        arrangeListingCreate(request, bricklinkDraftListing());
        Inventory publicLot = createdInventory();
        publicLot.setIs_stock_room(false);
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(lots());
        when(bricklinkRestClient.createInventory(any(Inventory.class))).thenReturn(resource(publicLot));
        when(bricklinkRestClient.getInventories(45678L)).thenReturn(resource(publicLot));
        when(bricklinkRestClient.updateInventory(eq(45678L), any(Inventory.class))).thenThrow(new RuntimeException("BrickLink down"));
        stubUpdates();

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        assertThat(request.getLastErrorMessage()).endsWith("; hiding lot 45678 in stockroom C failed, hide it by hand: BrickLink down");
    }

    @Test
    void releasesStaleClaimsBeforeClaimingNewWork() {
        applyAsSandbox();
        properties.setStaleClaimTimeout(Duration.ofHours(1));
        MarketplaceListingSyncRequest stale = claimedListingCreateRequest();
        stale.setClaimedAt(ZonedDateTime.now(ZoneOffset.UTC).minusHours(2));
        MarketplaceListingSyncRequest exhausted = claimedRequest();
        exhausted.setMarketplaceListingSyncRequestId(902L);
        exhausted.setAttemptCount(3);
        exhausted.setClaimedAt(ZonedDateTime.now(ZoneOffset.UTC).minusHours(3));
        MarketplaceListingSyncRequest recent = claimedRequest();
        recent.setMarketplaceListingSyncRequestId(903L);
        recent.setClaimedAt(ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        when(marketplaceListingSyncRequestDao.findBySyncRequestStatusCode("CLAIMED")).thenReturn(Set.of(stale, exhausted, recent));
        stubUpdates();

        service.runOnce();

        assertThat(stale.getSyncRequestStatusCode()).isEqualTo("PENDING");
        assertThat(stale.getClaimedAt()).isNull();
        assertThat(stale.getNextAttemptAt()).isNotNull();
        assertThat(stale.getLastErrorMessage()).startsWith("Released a stale claim from ");
        assertThat(exhausted.getSyncRequestStatusCode()).isEqualTo("FAILED");
        assertThat(recent.getSyncRequestStatusCode()).isEqualTo("CLAIMED");
        verify(marketplaceListingSyncRequestDao).update(stale);
        verify(marketplaceListingSyncRequestDao).update(exhausted);
        verify(marketplaceListingSyncRequestDao, never()).update(recent);
    }

    @Test
    void dryRunReleasesNoClaims() {
        properties.setMode("DRY_RUN");
        properties.setEnvironmentCode("sandbox");

        service.runOnce();

        verify(marketplaceListingSyncRequestDao, never()).findBySyncRequestStatusCode(any());
    }

    private void applyAsSandbox() {
        properties.setMode("APPLY");
        properties.setEnvironmentCode("sandbox");
    }

    /** One LISTING_CREATE request whose claim returns {@code request} itself, so its state carries across runs. */
    private void arrangeListingCreate(MarketplaceListingSyncRequest request, BricklinkMarketplaceListing mapping) {
        when(marketplaceListingSyncRequestDao.findClaimableByStatusCodeAndSyncRequestTypeCodes(
                eq("PENDING"),
                eq(Set.of("PRICE_UPDATE", "LISTING_CREATE")),
                any(ZonedDateTime.class),
                eq(5)
        ))
                .thenReturn(Set.of(request));
        when(marketplaceListingSyncRequestDao.claim(eq(901L), eq("PENDING"), eq("CLAIMED"), any(ZonedDateTime.class)))
                .thenAnswer(invocation -> {
                    request.setSyncRequestStatusCode("CLAIMED");
                    return Optional.of(request);
                });
        when(marketplaceListingDao.findByMarketplaceListingId(101)).thenReturn(Optional.of(listingCreateListing()));
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(101)).thenReturn(Optional.of(mapping));
        when(itemInventoryDao.findByItemInventoryId(201)).thenReturn(Optional.of(sellableItemInventory()));
    }

    private void stubUpdates() {
        when(marketplaceListingDao.update(any(MarketplaceListing.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(bricklinkMarketplaceListingDao.update(any(BricklinkMarketplaceListing.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketplaceListingSyncRequestDao.update(any(MarketplaceListingSyncRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private BricklinkResource<List<Inventory>> lots(Inventory... lots) {
        BricklinkResource<List<Inventory>> resource = new BricklinkResource<>();
        resource.setData(List.of(lots));
        return resource;
    }

    private Inventory lot(Long id, String remarks) {
        Inventory inventory = new Inventory();
        inventory.setInventory_id(id);
        inventory.setRemarks(remarks);
        return inventory;
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
