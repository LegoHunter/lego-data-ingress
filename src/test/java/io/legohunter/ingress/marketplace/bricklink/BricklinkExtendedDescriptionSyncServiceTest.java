package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.exception.BricklinkServerException;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.bricklink.BricklinkLotText;
import io.legohunter.data.dao.BricklinkLotTextSource;
import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dto.BricklinkListingCaption;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.ingress.marketplace.bricklink.BricklinkWebGatewayClient.Answer;
import io.legohunter.ingress.marketplace.bricklink.BricklinkWebGatewayClient.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BricklinkExtendedDescriptionSyncServiceTest {
    private static final long LOT = 12345L;
    private static final String TYPE = "EXTENDED_DESCRIPTION_UPDATE";

    private final BricklinkExtendedDescriptionSyncProperties properties = new BricklinkExtendedDescriptionSyncProperties();
    private final BricklinkMarketplaceSyncProperties syncProperties = new BricklinkMarketplaceSyncProperties();
    private final BricklinkWebGatewayProperties gatewayProperties = new BricklinkWebGatewayProperties();
    private final MarketplaceListingSyncRequestDao syncRequestDao = mock(MarketplaceListingSyncRequestDao.class);
    private final MarketplaceListingDao marketplaceListingDao = mock(MarketplaceListingDao.class);
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao = mock(BricklinkMarketplaceListingDao.class);
    private final ItemInventoryDao itemInventoryDao = mock(ItemInventoryDao.class);
    private final ItemInventoryPhotoDao itemInventoryPhotoDao = mock(ItemInventoryPhotoDao.class);
    private final BricklinkRestClient bricklinkRestClient = mock(BricklinkRestClient.class);
    private final BricklinkWebGatewayClient gateway = mock(BricklinkWebGatewayClient.class);
    private final BricklinkExtendedDescriptionSyncService service = new BricklinkExtendedDescriptionSyncService(
            properties, syncProperties, gatewayProperties, syncRequestDao, marketplaceListingDao, bricklinkMarketplaceListingDao,
            itemInventoryDao, new BricklinkLotTextSource(mock(ExternalImageAlbumDao.class), itemInventoryPhotoDao),
            bricklinkRestClient, new BricklinkRemoteInventorySafetyService(), gateway);

    private BricklinkMarketplaceListing mapping;

    @BeforeEach
    void sandboxInApplyWithOneListingWhoseItemHasTwoCaptions() {
        syncProperties.setEnvironmentCode("sandbox");
        syncProperties.setNonProdStockRoomId("C");
        syncProperties.setMode("APPLY");
        properties.setEnabled(true);
        properties.setMode("APPLY");
        gatewayProperties.setBaseUrl("http://bricklink-web-gateway:8080");
        gatewayProperties.setCallerToken("test-caller-token");

        mapping = BricklinkMarketplaceListing.builder().marketplaceListingId(100).bricklinkInventoryId((int) LOT).build();
        when(marketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(MarketplaceListing.builder()
                .marketplaceListingId(100).itemInventoryId(200).listingExternalServiceId(2).listingStatusCode("ACTIVE").build()));
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenAnswer(invocation -> Optional.of(mapping));
        ItemInventory item = new ItemInventory();
        item.setItemInventoryId(200);
        item.setUuid("inventory-uuid");
        when(itemInventoryDao.findByItemInventoryId(200)).thenReturn(Optional.of(item));
        when(bricklinkRestClient.getInventories(LOT)).thenReturn(resource(lotInStockroom("C")));
        when(itemInventoryPhotoDao.findProcessedCaptionsByItemInventoryId(200)).thenReturn(List.of("Box has a dent.", "Sticker sheet applied."));
        when(syncRequestDao.insert(any(MarketplaceListingSyncRequest.class))).thenAnswer(invocation -> {
            MarketplaceListingSyncRequest inserted = invocation.getArgument(0);
            inserted.setMarketplaceListingSyncRequestId(900L);
            return inserted;
        });
        when(syncRequestDao.update(any(MarketplaceListingSyncRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(syncRequestDao.findBySyncRequestStatusCode("CLAIMED")).thenReturn(Set.of());
        when(gateway.putExtendedDescription(anyLong(), anyString())).thenReturn(Answer.stored());
    }

    @Test
    void anOutOfDateLotIsQueuedSentAndRecorded() {
        captions(row(100, null, 1, "Box has a dent."), row(100, null, 2, "Sticker sheet applied."));
        waiting(request());

        BricklinkExtendedDescriptionSyncResult result = service.runOnce();

        ArgumentCaptor<MarketplaceListingSyncRequest> queued = ArgumentCaptor.forClass(MarketplaceListingSyncRequest.class);
        verify(syncRequestDao).insert(queued.capture());
        assertThat(queued.getValue().getSyncRequestTypeCode()).isEqualTo(TYPE);
        assertThat(queued.getValue().getRemoteInventoryId()).isEqualTo("12345");
        assertThat(queued.getValue().getRemoteVisibilityContainerId()).isEqualTo("C");
        assertThat(queued.getValue().getNextAttemptAt().getNano()).isZero();

        String text = "Box has a dent.\nSticker sheet applied.";
        verify(gateway).putExtendedDescription(LOT, text);
        verify(bricklinkMarketplaceListingDao).updateExtendedDescription(eq(100), eq(BricklinkLotText.hash(text)), any(ZonedDateTime.class));
        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("SUCCEEDED");
        assertThat(result.outcome()).isEqualTo("COMPLETED");
        assertThat(result.stored()).isOne();
    }

    @Test
    void aTextBricklinkCutShortIsRecordedButBlockedSoTheOwnerSeesIt() {
        captions(row(100, null, 1, "Box has a dent."), row(100, null, 2, "Sticker sheet applied."));
        waiting(request());
        when(gateway.putExtendedDescription(anyLong(), anyString()))
                .thenReturn(new Answer(Kind.STORED, null, null, "Box has a dent.\nSticker"));

        BricklinkExtendedDescriptionSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        verify(bricklinkMarketplaceListingDao).updateExtendedDescription(eq(100), any(), any(ZonedDateTime.class));
        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        assertThat(lastUpdate().getLastErrorMessage()).startsWith("EXTENDED_DESCRIPTION_TRUNCATED").contains("kept 23 of the 38");
    }

    @Test
    void htmlEscapingAndLineEndsAreNotTruncation() {
        assertThat(BricklinkExtendedDescriptionSyncService.truncation("Box \"dented\" & worn.\nOK", "Box &quot;dented&quot; &amp; worn.\r\nOK")).isNull();
        assertThat(BricklinkExtendedDescriptionSyncService.truncation("Box has a dent.", null)).isNull();
        assertThat(BricklinkExtendedDescriptionSyncService.truncation("", "")).isNull();
        assertThat(BricklinkExtendedDescriptionSyncService.truncation("abcdef", "abc")).contains("kept 3 of the 6");
    }

    @Test
    void aLotThatHoldsTheTextAlreadyIsNotQueued() {
        String hash = BricklinkLotText.hash("Box has a dent.\nSticker sheet applied.");
        captions(row(100, hash, 1, "Box has a dent."), row(100, hash, 2, "Sticker sheet applied."));

        BricklinkExtendedDescriptionSyncResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_WORK");
        verify(syncRequestDao, never()).insert(any());
    }

    @Test
    void anItemWithoutCaptionsNeverSentToIsLeftAlone() {
        captions(row(100, null, null, null));

        assertThat(service.outOfDateListingIds()).isEmpty();
    }

    @Test
    void aCaptionRemovedSinceTheLastSendClearsTheField() {
        mapping.setExtendedDescriptionHash(BricklinkLotText.hash("Box has a dent."));
        captions(row(100, mapping.getExtendedDescriptionHash(), null, null));
        when(itemInventoryPhotoDao.findProcessedCaptionsByItemInventoryId(200)).thenReturn(List.of());
        waiting(request());

        service.runOnce();

        verify(gateway).putExtendedDescription(LOT, "");
        verify(bricklinkMarketplaceListingDao).updateExtendedDescription(eq(100), eq(BricklinkLotText.hash("")), any(ZonedDateTime.class));
    }

    @Test
    void theTextIsBuiltAgainWhenSentAndACaughtUpLotIsClosedWithoutSending() {
        // Queued for an old text; by the time it is sent the recorded hash already matches the captions.
        mapping.setExtendedDescriptionHash(BricklinkLotText.hash("Box has a dent.\nSticker sheet applied."));
        waiting(request());

        BricklinkExtendedDescriptionSyncResult result = service.runOnce();

        assertThat(result.alreadyCurrent()).isOne();
        verify(gateway, never()).putExtendedDescription(anyLong(), anyString());
        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("SUCCEEDED");
    }

    @Test
    void dryRunChecksEverythingAndWritesNothing() {
        properties.setMode("DRY_RUN");
        captions(row(100, null, 1, "Box has a dent."));

        BricklinkExtendedDescriptionSyncResult result = service.runOnce();

        assertThat(result.mode()).isEqualTo(BricklinkMarketplaceSyncMode.DRY_RUN);
        assertThat(result.dryRunVerified()).isOne();
        verify(bricklinkRestClient).getInventories(LOT);
        verifyNoInteractions(gateway);
        verify(syncRequestDao, never()).insert(any());
        verify(bricklinkMarketplaceListingDao, never()).updateExtendedDescription(any(), any(), any());
    }

    @Test
    void itOnlyWritesWhenTheMarketplaceSyncAlsoWrites() {
        syncProperties.setMode("DRY_RUN");
        captions(row(100, null, 1, "Box has a dent."));

        assertThat(service.runOnce().mode()).isEqualTo(BricklinkMarketplaceSyncMode.DRY_RUN);
        verifyNoInteractions(gateway);
    }

    @Test
    void aSignedOutGatewayCostsNoAttemptAndStopsTheRun() {
        MarketplaceListingSyncRequest first = request();
        MarketplaceListingSyncRequest second = request();
        second.setMarketplaceListingSyncRequestId(901L);
        waiting(first, second);
        when(gateway.putExtendedDescription(anyLong(), anyString()))
                .thenReturn(new Answer(Kind.CANNOT_WORK, "SESSION_SIGNED_OUT", "The gateway is signed out"));

        BricklinkExtendedDescriptionSyncResult result = service.runOnce();

        assertThat(result.deferred()).isOne();
        assertThat(result.outcome()).isEqualTo("DEFERRED");
        verify(gateway, times(1)).putExtendedDescription(anyLong(), anyString());
        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("PENDING");
        assertThat(lastUpdate().getAttemptCount()).isZero();
        verify(bricklinkMarketplaceListingDao, never()).updateExtendedDescription(any(), any(), any());
    }

    @Test
    void aRefusalIsBlockedAndAFailureRetried() {
        waiting(request());
        when(gateway.putExtendedDescription(anyLong(), anyString()))
                .thenReturn(new Answer(Kind.REFUSED, "LOT_GUARD_REFUSED", "Not this environment's stockroom"));
        service.runOnce();
        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        assertThat(lastUpdate().getLastErrorMessage()).startsWith("LOT_GUARD_REFUSED");

        when(gateway.putExtendedDescription(anyLong(), anyString()))
                .thenReturn(new Answer(Kind.FAILED, "WRITE_NOT_APPLIED", "Unchanged after the update"));
        service.runOnce();
        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("PENDING");
        assertThat(lastUpdate().getLastErrorMessage()).startsWith("WRITE_NOT_APPLIED");
        verify(bricklinkMarketplaceListingDao, never()).updateExtendedDescription(any(), any(), any());
    }

    @Test
    void aLotOutsideTheStockroomIsBlockedBeforeTheGatewayIsAsked() {
        when(bricklinkRestClient.getInventories(LOT)).thenReturn(resource(lotInStockroom("A")));
        waiting(request());

        BricklinkExtendedDescriptionSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        verifyNoInteractions(gateway);
    }

    @Test
    void aBricklinkOutageCostsNoAttempt() {
        when(bricklinkRestClient.getInventories(LOT)).thenThrow(new BricklinkServerException(503, "Service Unavailable"));
        waiting(request());

        BricklinkExtendedDescriptionSyncResult result = service.runOnce();

        assertThat(result.deferred()).isOne();
        assertThat(lastUpdate().getAttemptCount()).isZero();
        verifyNoInteractions(gateway);
    }

    @Test
    void withoutAGatewayAddressOrTokenNothingIsQueuedOrSent() {
        gatewayProperties.setCallerToken("");
        captions(row(100, null, 1, "Box has a dent."));

        BricklinkExtendedDescriptionSyncResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("GATEWAY_NOT_CONFIGURED");
        verify(syncRequestDao, never()).insert(any());
        verifyNoInteractions(gateway);
    }

    @Test
    void theQueueLimitCapsOneRun() {
        properties.setQueueLimit(1);
        captions(row(100, null, 1, "a"), row(101, null, 2, "b"));

        assertThat(service.outOfDateListingIds()).containsExactly(100);
    }

    @Test
    void onlyAnApplyRunWritesSoOnlyItMayMentionTheExtendedDescription() {
        assertThat(properties.writes(BricklinkMarketplaceSyncMode.APPLY)).isTrue();
        assertThat(properties.writes(BricklinkMarketplaceSyncMode.DRY_RUN)).isFalse();
        properties.setMode("DRY_RUN");
        assertThat(properties.writes(BricklinkMarketplaceSyncMode.APPLY)).isFalse();
        properties.setMode("APPLY");
        properties.setEnabled(false);
        assertThat(properties.writes(BricklinkMarketplaceSyncMode.APPLY)).isFalse();
    }

    private void captions(BricklinkListingCaption... rows) {
        when(bricklinkMarketplaceListingDao.findListingCaptionsForExtendedDescription(
                eq(2), eq("sandbox"), eq(TYPE), any(ZonedDateTime.class))).thenReturn(List.of(rows));
    }

    private static BricklinkListingCaption row(Integer listingId, String hash, Integer photoId, String caption) {
        return new BricklinkListingCaption(listingId, hash, photoId, caption);
    }

    private void waiting(MarketplaceListingSyncRequest... requests) {
        when(syncRequestDao.findClaimableByStatusCodeAndSyncRequestTypeCodes(
                eq("PENDING"), eq(Set.of(TYPE)), any(ZonedDateTime.class), eq(3)))
                .thenReturn(new LinkedHashSet<>(List.of(requests)));
        for (MarketplaceListingSyncRequest request : requests) {
            when(syncRequestDao.claim(eq(request.getMarketplaceListingSyncRequestId()), eq("PENDING"), eq("CLAIMED"), any(ZonedDateTime.class)))
                    .thenAnswer(invocation -> Optional.of(claimed(request)));
        }
    }

    private static MarketplaceListingSyncRequest request() {
        return MarketplaceListingSyncRequest.builder()
                .marketplaceListingSyncRequestId(900L)
                .marketplaceListingId(100)
                .listingExternalServiceId(2)
                .syncRequestTypeCode(TYPE)
                .syncRequestStatusCode("PENDING")
                .remoteInventoryId("12345")
                .attemptCount(0)
                .maxAttempts(3)
                .build();
    }

    private static MarketplaceListingSyncRequest claimed(MarketplaceListingSyncRequest request) {
        return MarketplaceListingSyncRequest.builder()
                .marketplaceListingSyncRequestId(request.getMarketplaceListingSyncRequestId())
                .marketplaceListingId(request.getMarketplaceListingId())
                .listingExternalServiceId(request.getListingExternalServiceId())
                .syncRequestTypeCode(request.getSyncRequestTypeCode())
                .syncRequestStatusCode("CLAIMED")
                .remoteInventoryId(request.getRemoteInventoryId())
                .attemptCount(1)
                .maxAttempts(request.getMaxAttempts())
                .claimedAt(ZonedDateTime.now())
                .build();
    }

    private MarketplaceListingSyncRequest lastUpdate() {
        ArgumentCaptor<MarketplaceListingSyncRequest> captor = ArgumentCaptor.forClass(MarketplaceListingSyncRequest.class);
        verify(syncRequestDao, atLeastOnce()).update(captor.capture());
        return captor.getValue();
    }

    private static Inventory lotInStockroom(String stockroom) {
        Inventory inventory = new Inventory();
        inventory.setInventory_id(LOT);
        inventory.setIs_stock_room(true);
        inventory.setStock_room_id(stockroom);
        inventory.setRemarks("Human notes [SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=sandbox; MARKETPLACE_LISTING_ID=100; ITEM_INVENTORY_UUID=inventory-uuid [SYSTEM_END]");
        return inventory;
    }

    private static BricklinkResource<Inventory> resource(Inventory inventory) {
        BricklinkResource<Inventory> resource = new BricklinkResource<>();
        resource.setData(inventory);
        return resource;
    }
}
