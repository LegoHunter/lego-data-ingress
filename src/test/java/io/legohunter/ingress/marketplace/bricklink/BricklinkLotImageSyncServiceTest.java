package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.exception.BricklinkClientException;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.data.enums.PhotoStatus;
import io.legohunter.imaging.scaling.ImageScalingService;
import io.legohunter.ingress.marketplace.bricklink.BricklinkWebGatewayClient.Answer;
import io.legohunter.ingress.marketplace.bricklink.BricklinkWebGatewayClient.Kind;
import io.legohunter.ingress.s3.api.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

class BricklinkLotImageSyncServiceTest {
    private static final String MD5_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String MD5_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    private static final long LOT = 12345L;

    private final BricklinkLotImageSyncProperties properties = new BricklinkLotImageSyncProperties();
    private final BricklinkMarketplaceSyncProperties syncProperties = new BricklinkMarketplaceSyncProperties();
    private final MarketplaceListingSyncRequestDao syncRequestDao = mock(MarketplaceListingSyncRequestDao.class);
    private final MarketplaceListingDao marketplaceListingDao = mock(MarketplaceListingDao.class);
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao = mock(BricklinkMarketplaceListingDao.class);
    private final ItemInventoryDao itemInventoryDao = mock(ItemInventoryDao.class);
    private final ItemInventoryPhotoDao itemInventoryPhotoDao = mock(ItemInventoryPhotoDao.class);
    private final BricklinkRestClient bricklinkRestClient = mock(BricklinkRestClient.class);
    private final S3Service s3Service = mock(S3Service.class);
    private final ImageScalingService imageScalingService = mock(ImageScalingService.class);
    private final BricklinkWebGatewayClient gateway = mock(BricklinkWebGatewayClient.class);
    private final BricklinkLotImageSyncService service = new BricklinkLotImageSyncService(
            properties, syncProperties, syncRequestDao, marketplaceListingDao, bricklinkMarketplaceListingDao, itemInventoryDao,
            itemInventoryPhotoDao, bricklinkRestClient, new BricklinkRemoteInventorySafetyService(), s3Service, imageScalingService, gateway);

    private BricklinkMarketplaceListing mapping;

    @BeforeEach
    void sandboxInApplyWithOneListingWhoseLotHasNoImageYet() {
        syncProperties.setEnvironmentCode("sandbox");
        syncProperties.setNonProdStockRoomId("C");
        syncProperties.setMode("APPLY");
        properties.setMode("APPLY");
        properties.setGatewayBaseUrl("http://bricklink-web-gateway:8080");
        properties.setCallerToken("test-caller-token");

        mapping = BricklinkMarketplaceListing.builder().marketplaceListingId(100).bricklinkInventoryId((int) LOT).build();
        when(marketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(MarketplaceListing.builder()
                .marketplaceListingId(100).itemInventoryId(200).listingExternalServiceId(2).listingStatusCode("ACTIVE").build()));
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenAnswer(invocation -> Optional.of(mapping));
        ItemInventory item = new ItemInventory();
        item.setItemInventoryId(200);
        item.setUuid("inventory-uuid");
        when(itemInventoryDao.findByItemInventoryId(200)).thenReturn(Optional.of(item));
        when(bricklinkRestClient.getInventories(LOT)).thenReturn(resource(lotInStockroom("C")));
        when(itemInventoryPhotoDao.findProcessedPrimariesByItemInventoryId(200)).thenReturn(List.of(photo(MD5_A)));
        when(s3Service.getObject(anyString(), anyString())).thenAnswer(invocation -> new ByteArrayInputStream(JPEG));
        when(syncRequestDao.insert(any(MarketplaceListingSyncRequest.class))).thenAnswer(invocation -> {
            MarketplaceListingSyncRequest inserted = invocation.getArgument(0);
            inserted.setMarketplaceListingSyncRequestId(900L);
            return inserted;
        });
        when(syncRequestDao.update(any(MarketplaceListingSyncRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(syncRequestDao.findBySyncRequestStatusCode("CLAIMED")).thenReturn(Set.of());
        when(gateway.putLotImage(anyLong(), anyString(), any())).thenReturn(Answer.stored());
    }

    @Test
    void anOutOfDateLotIsQueuedSentAndRecorded() {
        outOfDate(100);
        waiting(request());

        BricklinkLotImageSyncResult result = service.runOnce();

        ArgumentCaptor<MarketplaceListingSyncRequest> queued = ArgumentCaptor.forClass(MarketplaceListingSyncRequest.class);
        verify(syncRequestDao).insert(queued.capture());
        assertThat(queued.getValue().getSyncRequestTypeCode()).isEqualTo("LOT_IMAGE_UPDATE");
        assertThat(queued.getValue().getRemoteInventoryId()).isEqualTo("12345");
        assertThat(queued.getValue().getRemoteVisibilityContainerId()).isEqualTo("C");
        assertThat(queued.getValue().getEnvironmentCode()).isEqualTo("sandbox");
        // Due at once: no fraction of a second for the database to round up past "now".
        assertThat(queued.getValue().getNextAttemptAt().getNano()).isZero();

        verify(gateway).putLotImage(LOT, "lot-12345.jpg", JPEG);
        verify(bricklinkMarketplaceListingDao).updateLotImage(eq(100), eq(MD5_A), any(ZonedDateTime.class));
        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("SUCCEEDED");
        assertThat(result.outcome()).isEqualTo("COMPLETED");
        assertThat(result.queued()).isOne();
        assertThat(result.stored()).isOne();
        verifyNoInteractions(imageScalingService);
    }

    @Test
    void dryRunChecksEverythingAndWritesNothing() {
        properties.setMode("DRY_RUN");
        outOfDate(100);

        BricklinkLotImageSyncResult result = service.runOnce();

        assertThat(result.mode()).isEqualTo(BricklinkMarketplaceSyncMode.DRY_RUN);
        assertThat(result.dryRunVerified()).isOne();
        verify(bricklinkRestClient).getInventories(LOT);
        verifyNoInteractions(gateway, s3Service);
        verify(syncRequestDao, never()).insert(any());
        verify(syncRequestDao, never()).claim(any(), any(), any(), any());
        verify(bricklinkMarketplaceListingDao, never()).updateLotImage(any(), any(), any());
    }

    @Test
    void itOnlyWritesWhenTheMarketplaceSyncAlsoWrites() {
        syncProperties.setMode("DRY_RUN");
        outOfDate(100);

        assertThat(service.runOnce().mode()).isEqualTo(BricklinkMarketplaceSyncMode.DRY_RUN);
        verifyNoInteractions(gateway);
    }

    @Test
    void aSignedOutGatewayCostsNoAttemptAndStopsTheRun() {
        MarketplaceListingSyncRequest first = request();
        MarketplaceListingSyncRequest second = request();
        second.setMarketplaceListingSyncRequestId(901L);
        waiting(first, second);
        when(gateway.putLotImage(anyLong(), anyString(), any()))
                .thenReturn(new Answer(Kind.CANNOT_WORK, "SESSION_SIGNED_OUT", "The gateway is signed out"));

        BricklinkLotImageSyncResult result = service.runOnce();

        MarketplaceListingSyncRequest deferred = lastUpdate();
        assertThat(deferred.getSyncRequestStatusCode()).isEqualTo("PENDING");
        assertThat(deferred.getAttemptCount()).isZero();
        assertThat(deferred.getNextAttemptAt()).isAfter(ZonedDateTime.now().plusHours(5));
        assertThat(deferred.getLastErrorMessage()).contains("SESSION_SIGNED_OUT");
        assertThat(result.outcome()).isEqualTo("DEFERRED");
        assertThat(result.deferred()).isOne();
        // The second request is not even claimed.
        verify(gateway, times(1)).putLotImage(anyLong(), anyString(), any());
        verify(syncRequestDao, times(1)).claim(any(), any(), any(), any());
        verify(bricklinkMarketplaceListingDao, never()).updateLotImage(any(), any(), any());
    }

    @Test
    void aLotOutsideThisEnvironmentsStockroomIsBlockedBeforeTheGatewayIsAsked() {
        waiting(request());
        when(bricklinkRestClient.getInventories(LOT)).thenReturn(resource(lotInStockroom("B")));

        BricklinkLotImageSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        verifyNoInteractions(gateway, s3Service);
    }

    @Test
    void aRefusalByTheGatewayBlocksTheRequest() {
        waiting(request());
        when(gateway.putLotImage(anyLong(), anyString(), any()))
                .thenReturn(new Answer(Kind.REFUSED, "LOT_GUARD_REFUSED", "Lot 12345 is in stockroom B"));

        service.runOnce();

        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        assertThat(lastUpdate().getLastErrorMessage()).startsWith("LOT_GUARD_REFUSED");
        verify(bricklinkMarketplaceListingDao, never()).updateLotImage(any(), any(), any());
    }

    @Test
    void aFailedReplaceForgetsTheRecordedImageAndRetries() {
        mapping.setLotImageMd5(MD5_B);
        waiting(request());
        when(gateway.putLotImage(anyLong(), anyString(), any()))
                .thenReturn(new Answer(Kind.FAILED, "WRITE_NOT_APPLIED", "The lot shows no custom image after the upload"));

        BricklinkLotImageSyncResult result = service.runOnce();

        // The gateway deleted the old image before the upload failed, so the lot shows none now.
        verify(bricklinkMarketplaceListingDao).updateLotImage(100, null, null);
        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("PENDING");
        assertThat(lastUpdate().getAttemptCount()).isOne();
        assertThat(result.failed()).isOne();
    }

    @Test
    void theLastFailedAttemptEndsTheRequest() {
        MarketplaceListingSyncRequest request = request();
        waiting(request);
        MarketplaceListingSyncRequest lastAttempt = claimed(request);
        lastAttempt.setAttemptCount(3);
        when(syncRequestDao.claim(eq(900L), eq("PENDING"), eq("CLAIMED"), any(ZonedDateTime.class))).thenReturn(Optional.of(lastAttempt));
        when(gateway.putLotImage(anyLong(), anyString(), any())).thenReturn(new Answer(Kind.FAILED, "UNEXPECTED_PAGE", "why"));

        service.runOnce();

        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("FAILED");
    }

    @Test
    void withSeveralPrimaryPhotosOneIsSentAndItIsReported() {
        waiting(request());
        when(itemInventoryPhotoDao.findProcessedPrimariesByItemInventoryId(200)).thenReturn(List.of(photo(MD5_A), photo(MD5_B)));

        BricklinkLotImageSyncResult result = service.runOnce();

        verify(bricklinkMarketplaceListingDao).updateLotImage(eq(100), eq(MD5_A), any(ZonedDateTime.class));
        assertThat(result.multiplePrimaries()).isOne();
        assertThat(result.stored()).isOne();
    }

    @Test
    void aLotThatAlreadyShowsOneOfThePrimaryPhotosIsLeftAlone() {
        mapping.setLotImageMd5(MD5_B);
        waiting(request());
        when(itemInventoryPhotoDao.findProcessedPrimariesByItemInventoryId(200)).thenReturn(List.of(photo(MD5_A), photo(MD5_B)));

        BricklinkLotImageSyncResult result = service.runOnce();

        assertThat(result.alreadyCurrent()).isOne();
        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("SUCCEEDED");
        verifyNoInteractions(gateway, s3Service);
    }

    @Test
    void anItemThatLostItsPrimaryPhotoSendsNothing() {
        waiting(request());
        when(itemInventoryPhotoDao.findProcessedPrimariesByItemInventoryId(200)).thenReturn(List.of());

        service.runOnce();

        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        assertThat(lastUpdate().getLastErrorMessage()).startsWith("NO_PRIMARY_PHOTO");
        verifyNoInteractions(gateway);
    }

    @Test
    void aPhotoAboveTheSizeLimitIsScaledBeforeItIsSent() {
        properties.setMaxImageBytes(5);
        waiting(request());
        byte[] scaled = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 9};
        when(imageScalingService.scale(JPEG)).thenReturn(scaled);

        service.runOnce();

        verify(gateway).putLotImage(LOT, "lot-12345.jpg", scaled);
    }

    @Test
    void aPhotoThatIsNeitherJpegNorGifIsBlockedBeforeTheGatewayWouldDeleteTheOldImage() {
        waiting(request());
        when(s3Service.getObject(anyString(), anyString()))
                .thenAnswer(invocation -> new ByteArrayInputStream(new byte[] {(byte) 0x89, 'P', 'N', 'G'}));

        service.runOnce();

        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("BLOCKED");
        assertThat(lastUpdate().getLastErrorMessage()).startsWith("UNSUPPORTED_IMAGE_FORMAT");
        verifyNoInteractions(gateway);
    }

    @Test
    void aBricklinkApiOutageDefersLikeAGatewayThatCannotWork() {
        waiting(request());
        when(bricklinkRestClient.getInventories(LOT)).thenThrow(new BricklinkClientException(302,
                "GET https://api.bricklink.com/api/store/v1/inventories/12345",
                "Unexpected redirect to [https://maintenance.bricklink.com/]. Response body: []"));

        BricklinkLotImageSyncResult result = service.runOnce();

        assertThat(result.deferred()).isOne();
        assertThat(lastUpdate().getSyncRequestStatusCode()).isEqualTo("PENDING");
        assertThat(lastUpdate().getAttemptCount()).isZero();
        verifyNoInteractions(gateway);
    }

    @Test
    void withoutAGatewayAddressOrTokenNothingIsQueuedOrSent() {
        properties.setCallerToken("");
        outOfDate(100);

        BricklinkLotImageSyncResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("GATEWAY_NOT_CONFIGURED");
        verify(syncRequestDao, never()).insert(any());
        verifyNoInteractions(gateway);
    }

    @Test
    void aClaimLeftByADeadWorkerIsReleased() {
        MarketplaceListingSyncRequest stale = claimed(request());
        stale.setClaimedAt(ZonedDateTime.now().minusHours(2));
        MarketplaceListingSyncRequest otherType = claimed(request());
        otherType.setSyncRequestTypeCode("PRICE_UPDATE");
        otherType.setClaimedAt(ZonedDateTime.now().minusHours(2));
        when(syncRequestDao.findBySyncRequestStatusCode("CLAIMED")).thenReturn(new LinkedHashSet<>(List.of(stale, otherType)));

        service.runOnce();

        verify(syncRequestDao, times(1)).update(any());
        assertThat(stale.getSyncRequestStatusCode()).isEqualTo("PENDING");
        assertThat(otherType.getSyncRequestStatusCode()).isEqualTo("CLAIMED");
    }

    @Test
    void theTokenIsNeverPrinted() {
        assertThat(properties.toString()).doesNotContain("test-caller-token").contains("<set>");
    }

    private void outOfDate(Integer... listingIds) {
        when(bricklinkMarketplaceListingDao.findMarketplaceListingIdsNeedingLotImage(
                eq(2), eq("sandbox"), eq("LOT_IMAGE_UPDATE"), any(ZonedDateTime.class), anyInt())).thenReturn(List.of(listingIds));
    }

    private void waiting(MarketplaceListingSyncRequest... requests) {
        when(syncRequestDao.findClaimableByStatusCodeAndSyncRequestTypeCodes(
                eq("PENDING"), eq(Set.of("LOT_IMAGE_UPDATE")), any(ZonedDateTime.class), eq(3)))
                .thenReturn(new LinkedHashSet<>(List.of(requests)));
        for (MarketplaceListingSyncRequest request : requests) {
            when(syncRequestDao.claim(eq(request.getMarketplaceListingSyncRequestId()), eq("PENDING"), eq("CLAIMED"), any(ZonedDateTime.class)))
                    .thenReturn(Optional.of(claimed(request)));
        }
    }

    private static MarketplaceListingSyncRequest request() {
        return MarketplaceListingSyncRequest.builder()
                .marketplaceListingSyncRequestId(900L)
                .marketplaceListingId(100)
                .listingExternalServiceId(2)
                .syncRequestTypeCode("LOT_IMAGE_UPDATE")
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

    private static ItemInventoryPhoto photo(String md5) {
        return ItemInventoryPhoto.builder().itemInventoryId(200).md5(md5).s3Bucket("lego-photos-sandbox").s3Key("k/" + md5 + ".jpg")
                .fileSize((long) JPEG.length).primary(true).status(PhotoStatus.PROCESSED).build();
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
