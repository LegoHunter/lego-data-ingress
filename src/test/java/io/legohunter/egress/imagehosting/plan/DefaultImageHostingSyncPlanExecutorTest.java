package io.legohunter.egress.imagehosting.plan;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ExternalImageAlbumImageDao;
import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.egress.imagehosting.ImageHostingRetryTemplate;
import io.legohunter.egress.imagehosting.ImageHostingSyncMetricsService;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.egress.imagehosting.publishing.ImageHostingPublishingPolicy;
import io.legohunter.egress.imagehosting.shorturl.ImageHostingShortUrlResult;
import io.legohunter.egress.imagehosting.shorturl.ImageHostingShortUrlService;
import io.legohunter.egress.imagehosting.shorturl.ImageHostingShortUrlStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumCreateRequest;
import io.legohunter.imaging.model.HostedAlbumMembershipRequest;
import io.legohunter.imaging.model.PhotoMetaDataV1;
import io.legohunter.imaging.model.PhotoServiceErrorType;
import io.legohunter.imaging.model.PhotoServiceRequest;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import io.legohunter.imaging.service.sync.model.SyncAction;
import io.legohunter.imaging.service.sync.model.SyncActionResult;
import io.legohunter.imaging.service.sync.model.SyncActionSafety;
import io.legohunter.imaging.service.sync.model.SyncActionStatus;
import io.legohunter.imaging.service.sync.model.SyncActionType;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.imaging.service.sync.model.SyncPlanMode;
import io.legohunter.imaging.service.sync.model.SyncReport;
import io.legohunter.ingress.s3.api.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static io.legohunter.data.enums.ExternalSyncStatus.FAILED;
import static io.legohunter.data.enums.ExternalSyncStatus.SYNCED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.groups.Tuple.tuple;

@ExtendWith(MockitoExtension.class)
class DefaultImageHostingSyncPlanExecutorTest {
    private static final int FLICKR_SERVICE_ID = 10;

    @TempDir
    private Path tempDirectory;

    @Mock
    private ImageHostingService imageHostingService;

    @Mock
    private S3Service s3Service;

    @Mock
    private ItemInventoryDao itemInventoryDao;

    @Mock
    private ItemInventoryPhotoDao itemInventoryPhotoDao;

    @Mock
    private ExternalImageDao externalImageDao;

    @Mock
    private ExternalImageAlbumDao externalImageAlbumDao;

    @Mock
    private ExternalImageAlbumImageDao externalImageAlbumImageDao;

    @Mock
    private ImageHostingShortUrlService shortUrlService;

    private SimpleMeterRegistry meterRegistry;
    private DefaultImageHostingSyncPlanExecutor executor;

    @BeforeEach
    void setUp() {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.getSync().setTempDirectory(tempDirectory);
        properties.getSync().getRetry().setInitialBackoffMs(0L);
        meterRegistry = new SimpleMeterRegistry();
        executor = new DefaultImageHostingSyncPlanExecutor(
                Optional.of(imageHostingService),
                s3Service,
                itemInventoryDao,
                itemInventoryPhotoDao,
                externalImageDao,
                externalImageAlbumDao,
                externalImageAlbumImageDao,
                properties,
                new ImageHostingRetryTemplate(properties),
                new ImageHostingPublishingPolicy(properties),
                shortUrlService,
                new ImageHostingSyncMetricsService(meterRegistry)
        );
    }

    @Test
    void executeUploadsPhotoAndPersistsSyncedExternalImage() {
        ItemInventoryPhoto photo = photo(11, true);
        when(itemInventoryPhotoDao.findByItemInventoryPhotoId(11)).thenReturn(Optional.of(photo));
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.empty());
        when(s3Service.getObject("photos", "100/front.jpg"))
                .thenReturn(new ByteArrayInputStream("image-data".getBytes()));
        when(imageHostingService.uploadPhoto(any())).thenReturn(response("flickr-photo-11"));
        when(externalImageDao.upsert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SyncReport report = executor.execute(SyncPlan.builder()
                .planId("plan-1")
                .action(uploadAction())
                .build(), false);

        assertThat(report.getMode()).isEqualTo(SyncPlanMode.APPLY);
        assertThat(report.getSummary().getSucceeded()).isEqualTo(1);
        assertThat(report.getResults().getFirst())
                .extracting(SyncActionResult::getStatus, SyncActionResult::getPhotoId)
                .containsExactly(SyncActionStatus.SUCCEEDED, "flickr-photo-11");

        ArgumentCaptor<ExternalImage> imageCaptor = ArgumentCaptor.forClass(ExternalImage.class);
        verify(externalImageDao).upsert(imageCaptor.capture());
        assertThat(imageCaptor.getValue())
                .extracting(
                        ExternalImage::getExternalServiceId,
                        ExternalImage::getItemInventoryPhotoId,
                        ExternalImage::getExternalServiceImageId,
                        ExternalImage::getTitle,
                        ExternalImage::getMd5AtUpload,
                        ExternalImage::getMetadataHashAtSync,
                        ExternalImage::getSyncStatus
                )
                .containsExactly(FLICKR_SERVICE_ID, 11, "flickr-photo-11", "Front caption", "md5-11", "metadata-11", SYNCED);

        ArgumentCaptor<PhotoServiceRequest<PhotoMetaDataV1>> uploadCaptor = ArgumentCaptor.forClass(PhotoServiceRequest.class);
        verify(imageHostingService).uploadPhoto(uploadCaptor.capture());
        assertThat(uploadCaptor.getValue().get().getUploadMetadata())
                .extracting(
                        metadata -> metadata.getTitle(),
                        metadata -> metadata.getDescription(),
                        metadata -> metadata.getPublicFlag(),
                        metadata -> metadata.getSafetyLevel()
                )
                .containsExactly("Front caption", "Front caption", true, "safe");
    }

    @Test
    void executeBlocksReviewRequiredActionUnlessAllowed() {
        SyncReport report = executor.execute(SyncPlan.builder()
                .planId("plan-1")
                .action(SyncAction.builder()
                        .actionId("001-repair-photo-id")
                        .type(SyncActionType.REPAIR_PHOTO_ID)
                        .safety(SyncActionSafety.REQUIRES_REVIEW)
                        .attribute("externalImageId", "201")
                        .build())
                .build(), false);

        assertThat(report.getResults().getFirst())
                .extracting(SyncActionResult::getStatus, SyncActionResult::getMessage)
                .containsExactly(SyncActionStatus.BLOCKED, "Action requires review before apply");
        verifyNoInteractions(externalImageDao, imageHostingService);
    }

    @Test
    void executeUpdatesAlbumMembershipAndPersistsMembershipRows() {
        ExternalImageAlbum album = album();
        ExternalImage primaryImage = externalImage(201L, 11, "photo-11");
        ExternalImage detailImage = externalImage(202L, 12, "photo-12");
        when(imageHostingService.updateAlbumMembership(any())).thenReturn(response(null));
        when(externalImageAlbumDao.findByExternalImageAlbumId(301L)).thenReturn(Optional.of(album));
        when(externalImageDao.findByExternalServiceIdAndExternalServiceImageId(FLICKR_SERVICE_ID, "photo-11"))
                .thenReturn(Optional.of(primaryImage));
        when(externalImageDao.findByExternalServiceIdAndExternalServiceImageId(FLICKR_SERVICE_ID, "photo-12"))
                .thenReturn(Optional.of(detailImage));

        SyncReport report = executor.execute(SyncPlan.builder()
                .planId("plan-1")
                .action(membershipAction())
                .build(), false);

        assertThat(report.getResults().getFirst().getStatus()).isEqualTo(SyncActionStatus.SUCCEEDED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<PhotoServiceRequest<HostedAlbumMembershipRequest>> requestCaptor =
                ArgumentCaptor.forClass(PhotoServiceRequest.class);
        verify(imageHostingService).updateAlbumMembership(requestCaptor.capture());
        assertThat(requestCaptor.getValue().get())
                .extracting(
                        HostedAlbumMembershipRequest::getAlbumId,
                        HostedAlbumMembershipRequest::getPrimaryPhotoId
                )
                .containsExactly("album-100", "photo-11");
        assertThat(requestCaptor.getValue().get().getPhotoIds()).containsExactly("photo-11", "photo-12");

        verify(externalImageAlbumImageDao).deleteByExternalImageAlbumId(301L);
        ArgumentCaptor<ExternalImageAlbumImage> membershipCaptor = ArgumentCaptor.forClass(ExternalImageAlbumImage.class);
        verify(externalImageAlbumImageDao, times(2)).upsert(membershipCaptor.capture());
        assertThat(membershipCaptor.getAllValues())
                .extracting(
                        ExternalImageAlbumImage::getExternalImageAlbumId,
                        ExternalImageAlbumImage::getExternalImageId,
                        ExternalImageAlbumImage::getSortOrder,
                        ExternalImageAlbumImage::getPrimary
                )
                .containsExactly(
                        tuple(301L, 201L, 1, true),
                        tuple(301L, 202L, 2, false)
                );
    }

    @Test
    void executeAdoptsExistingRemoteAlbumFromRepairAction() {
        ExternalImageAlbum album = ExternalImageAlbum.builder()
                .externalImageAlbumId(301L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryId(100)
                .build();
        when(externalImageAlbumDao.findByExternalServiceIdAndExternalAlbumId(FLICKR_SERVICE_ID, "album-100"))
                .thenReturn(Optional.empty());
        when(externalImageAlbumDao.findByExternalImageAlbumId(301L)).thenReturn(Optional.of(album));
        when(shortUrlService.recoverExistingShortUrl("https://flickr.example/albums/album-100"))
                .thenReturn(shortUrlResult(ImageHostingShortUrlStatus.RECOVERED_EXISTING, "https://bit.ly/album-100"));

        SyncReport report = executor.execute(SyncPlan.builder()
                .planId("repair-plan-1")
                .action(SyncAction.builder()
                        .actionId("001-repair-album-id")
                        .type(SyncActionType.REPAIR_ALBUM_ID)
                        .safety(SyncActionSafety.SAFE_AUTOMATIC)
                        .attribute("provider", "flickr")
                        .attribute("externalServiceId", Integer.toString(FLICKR_SERVICE_ID))
                        .attribute("itemInventoryId", "100")
                        .attribute("externalImageAlbumId", "301")
                        .attribute("remoteAlbumId", "album-100")
                        .attribute("remoteAlbumUrl", "https://flickr.example/albums/album-100")
                        .attribute("remoteTitle", "4558-1 - Metroliner")
                        .build())
                .build(), false);

        assertThat(report.getResults().getFirst())
                .extracting(SyncActionResult::getStatus, SyncActionResult::getAlbumId)
                .containsExactly(SyncActionStatus.SUCCEEDED, "album-100");
        assertThat(album)
                .extracting(
                        ExternalImageAlbum::getExternalAlbumId,
                        ExternalImageAlbum::getAlbumUrl,
                        ExternalImageAlbum::getShortUrl,
                        ExternalImageAlbum::getTitle,
                        ExternalImageAlbum::getSyncStatus
                )
                .containsExactly(
                        "album-100",
                        "https://flickr.example/albums/album-100",
                        "https://bit.ly/album-100",
                        "4558-1 - Metroliner",
                        SYNCED
                );
        verify(externalImageAlbumDao).update(album);
        verifyNoInteractions(imageHostingService);
        assertThat(meterRegistry.counter(
                "image_hosting_album_adoption",
                "provider", "flickr",
                "result", "adopted"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "image_hosting_short_url",
                "provider", "flickr",
                "operation", "recover",
                "result", "recovered"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void executeAdoptsExistingRemotePhotoFromRepairAction() {
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImage image = externalImage(201L, 11, null);
        when(externalImageDao.findByExternalServiceIdAndExternalServiceImageId(FLICKR_SERVICE_ID, "photo-11"))
                .thenReturn(Optional.empty());
        when(itemInventoryPhotoDao.findByItemInventoryPhotoId(11)).thenReturn(Optional.of(photo));
        when(externalImageDao.findByExternalImageId(201L)).thenReturn(Optional.of(image));

        SyncReport report = executor.execute(SyncPlan.builder()
                .planId("repair-plan-1")
                .action(SyncAction.builder()
                        .actionId("002-repair-photo-id")
                        .type(SyncActionType.REPAIR_PHOTO_ID)
                        .safety(SyncActionSafety.SAFE_AUTOMATIC)
                        .attribute("externalServiceId", Integer.toString(FLICKR_SERVICE_ID))
                        .attribute("itemInventoryPhotoId", "11")
                        .attribute("externalImageId", "201")
                        .attribute("remotePhotoId", "photo-11")
                        .attribute("remotePhotoUrl", "https://flickr.example/photos/photo-11")
                        .attribute("remoteTitle", "Front caption")
                        .build())
                .build(), false);

        assertThat(report.getResults().getFirst())
                .extracting(SyncActionResult::getStatus, SyncActionResult::getPhotoId)
                .containsExactly(SyncActionStatus.SUCCEEDED, "photo-11");
        assertThat(image)
                .extracting(
                        ExternalImage::getExternalServiceImageId,
                        ExternalImage::getImageUrl,
                        ExternalImage::getTitle,
                        ExternalImage::getMd5AtUpload,
                        ExternalImage::getMetadataHashAtSync,
                        ExternalImage::getSyncStatus
                )
                .containsExactly("photo-11", "https://flickr.example/photos/photo-11", "Front caption", "md5-11", "metadata-11", SYNCED);
        verify(externalImageDao).upsert(image);
        verifyNoInteractions(imageHostingService);
    }

    @Test
    void executeRepairsDbAlbumMembershipFromRemotePhotoIdsWithoutCallingProvider() {
        ExternalImageAlbum album = album();
        ExternalImage primaryImage = externalImage(201L, 11, "photo-11");
        ExternalImage detailImage = externalImage(202L, 12, "photo-12");
        when(externalImageAlbumDao.findByExternalImageAlbumId(301L)).thenReturn(Optional.of(album));
        when(externalImageDao.findByExternalServiceIdAndExternalServiceImageId(FLICKR_SERVICE_ID, "photo-11"))
                .thenReturn(Optional.of(primaryImage));
        when(externalImageDao.findByExternalServiceIdAndExternalServiceImageId(FLICKR_SERVICE_ID, "photo-12"))
                .thenReturn(Optional.of(detailImage));

        SyncReport report = executor.execute(SyncPlan.builder()
                .planId("repair-plan-1")
                .action(SyncAction.builder()
                        .actionId("004-update-album-membership")
                        .type(SyncActionType.UPDATE_ALBUM_MEMBERSHIP)
                        .safety(SyncActionSafety.SAFE_AUTOMATIC)
                        .attribute("externalServiceId", Integer.toString(FLICKR_SERVICE_ID))
                        .attribute("itemInventoryId", "100")
                        .attribute("externalImageAlbumId", "301")
                        .attribute("externalAlbumId", "album-100")
                        .attribute("remotePhotoIds", "photo-11,photo-12")
                        .attribute("remotePrimaryPhotoId", "photo-11")
                        .build())
                .build(), false);

        assertThat(report.getResults().getFirst())
                .extracting(SyncActionResult::getStatus, SyncActionResult::getAlbumId, SyncActionResult::getPhotoId)
                .containsExactly(SyncActionStatus.SUCCEEDED, "album-100", "photo-11");
        verify(externalImageAlbumImageDao).deleteByExternalImageAlbumId(301L);
        ArgumentCaptor<ExternalImageAlbumImage> membershipCaptor = ArgumentCaptor.forClass(ExternalImageAlbumImage.class);
        verify(externalImageAlbumImageDao, times(2)).upsert(membershipCaptor.capture());
        assertThat(membershipCaptor.getAllValues())
                .extracting(
                        ExternalImageAlbumImage::getExternalImageAlbumId,
                        ExternalImageAlbumImage::getExternalImageId,
                        ExternalImageAlbumImage::getSortOrder,
                        ExternalImageAlbumImage::getPrimary
                )
                .containsExactly(
                        tuple(301L, 201L, 1, true),
                        tuple(301L, 202L, 2, false)
                );
        verify(externalImageAlbumDao).update(album);
        verifyNoInteractions(imageHostingService);
    }

    @Test
    void executeCreatesAlbumFromDbBackedCreateRequest() {
        ItemInventoryPhoto primaryPhoto = photo(11, true);
        ItemInventoryPhoto detailPhoto = photo(12, false);
        ExternalImageAlbum pendingAlbum = ExternalImageAlbum.builder()
                .externalImageAlbumId(301L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryId(100)
                .title("4558-1 - Metroliner")
                .build();
        ExternalImage primaryImage = externalImage(201L, 11, "photo-11");
        ExternalImage detailImage = externalImage(202L, 12, "photo-12");
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(100);
        inventory.setUuid("inventory-uuid");
        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(FLICKR_SERVICE_ID, 100))
                .thenReturn(Optional.of(pendingAlbum));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(detailPhoto, primaryPhoto));
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.of(primaryImage));
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 12))
                .thenReturn(Optional.of(detailImage));
        when(imageHostingService.createAlbum(any())).thenReturn(response(HostedAlbum.builder()
                .id("album-100")
                .url("https://flickr.example/albums/album-100")
                .build()));
        when(shortUrlService.generateShortUrl("https://flickr.example/albums/album-100"))
                .thenReturn(shortUrlResult(ImageHostingShortUrlStatus.GENERATED_NEW, "https://bit.ly/album-100"));

        SyncReport report = executor.execute(SyncPlan.builder()
                .planId("plan-1")
                .action(SyncAction.builder()
                        .actionId("001-create-album")
                        .type(SyncActionType.CREATE_ALBUM)
                        .safety(SyncActionSafety.SAFE_AUTOMATIC)
                        .attribute("provider", "flickr")
                        .attribute("externalServiceId", Integer.toString(FLICKR_SERVICE_ID))
                        .attribute("itemInventoryId", "100")
                        .attribute("desiredTitle", "4558-1 - Metroliner")
                        .attribute("desiredDescription", "Generated album description")
                        .build())
                .build(), false);

        assertThat(report.getResults().getFirst().getStatus()).isEqualTo(SyncActionStatus.SUCCEEDED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<PhotoServiceRequest<HostedAlbumCreateRequest>> requestCaptor =
                ArgumentCaptor.forClass(PhotoServiceRequest.class);
        verify(imageHostingService).createAlbum(requestCaptor.capture());
        assertThat(requestCaptor.getValue().get())
                .extracting(
                        HostedAlbumCreateRequest::getTitle,
                        HostedAlbumCreateRequest::getDescription,
                        HostedAlbumCreateRequest::getPrimaryPhotoId
                )
                .containsExactly("4558-1 - Metroliner", "Generated album description", "photo-11");
        assertThat(requestCaptor.getValue().get().getPhotoIds()).containsExactly("photo-11", "photo-12");
        assertThat(pendingAlbum.getShortUrl()).isEqualTo("https://bit.ly/album-100");
        assertThat(meterRegistry.counter(
                "image_hosting_album_creation",
                "provider", "flickr",
                "result", "created"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "image_hosting_short_url",
                "provider", "flickr",
                "operation", "generate",
                "result", "generated"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void executeMarksPhotoMetadataUpdateFailedWhenProviderFails() {
        ExternalImage externalImage = externalImage(201L, 11, "photo-11");
        when(externalImageDao.findByExternalImageId(201L)).thenReturn(Optional.of(externalImage));
        when(imageHostingService.updatePhotoMetadata(any()))
                .thenReturn(errorResponse(PhotoServiceErrorType.SERVICE_UNAVAILABLE, 503, "Flickr unavailable"));

        SyncReport report = executor.execute(SyncPlan.builder()
                .planId("plan-1")
                .action(SyncAction.builder()
                        .actionId("001-update-photo-metadata")
                        .type(SyncActionType.UPDATE_PHOTO_METADATA)
                        .safety(SyncActionSafety.SAFE_AUTOMATIC)
                        .photoId("photo-11")
                        .attribute("externalServiceId", Integer.toString(FLICKR_SERVICE_ID))
                        .attribute("itemInventoryPhotoId", "11")
                        .attribute("externalImageId", "201")
                        .attribute("desiredTitle", "New title")
                        .attribute("desiredDescription", "New description")
                        .build())
                .build(), false);

        assertThat(report.getResults().getFirst())
                .extracting(
                        SyncActionResult::getStatus,
                        SyncActionResult::getResponseCode,
                        SyncActionResult::getMessage,
                        SyncActionResult::getErrorType
                )
                .containsExactly(SyncActionStatus.FAILED, 503, "Flickr unavailable", PhotoServiceErrorType.SERVICE_UNAVAILABLE);
        assertThat(externalImage.getSyncStatus()).isEqualTo(FAILED);
        assertThat(externalImage.getErrorMessage()).isEqualTo("Flickr unavailable");
        verify(externalImageDao).update(externalImage);
    }

    @Test
    void executeRetriesTransientProviderFailuresBeforeSucceeding() {
        ExternalImage externalImage = externalImage(201L, 11, "photo-11");
        when(externalImageDao.findByExternalImageId(201L)).thenReturn(Optional.of(externalImage));
        when(itemInventoryPhotoDao.findByItemInventoryPhotoId(11)).thenReturn(Optional.of(photo(11, true)));
        when(imageHostingService.updatePhotoMetadata(any()))
                .thenReturn(errorResponse(PhotoServiceErrorType.SERVICE_UNAVAILABLE, 503, "Flickr unavailable"))
                .thenReturn(response(null));

        SyncReport report = executor.execute(SyncPlan.builder()
                .planId("plan-1")
                .action(SyncAction.builder()
                        .actionId("001-update-photo-metadata")
                        .type(SyncActionType.UPDATE_PHOTO_METADATA)
                        .safety(SyncActionSafety.SAFE_AUTOMATIC)
                        .photoId("photo-11")
                        .attribute("externalServiceId", Integer.toString(FLICKR_SERVICE_ID))
                        .attribute("itemInventoryPhotoId", "11")
                        .attribute("externalImageId", "201")
                        .attribute("desiredTitle", "New title")
                        .attribute("desiredDescription", "New description")
                        .build())
                .build(), false);

        assertThat(report.getResults().getFirst())
                .extracting(
                        SyncActionResult::getStatus,
                        SyncActionResult::getAttempts,
                        SyncActionResult::isRetried,
                        SyncActionResult::isRetryable
                )
                .containsExactly(SyncActionStatus.SUCCEEDED, 2, true, false);
        verify(imageHostingService, times(2)).updatePhotoMetadata(any());
        verify(externalImageDao).update(externalImage);
    }

    @Test
    void executeDoesNotRetryNonTransientProviderFailures() {
        ExternalImage externalImage = externalImage(201L, 11, "photo-11");
        when(externalImageDao.findByExternalImageId(201L)).thenReturn(Optional.of(externalImage));
        when(imageHostingService.updatePhotoMetadata(any()))
                .thenReturn(errorResponse(PhotoServiceErrorType.AUTHORIZATION_FAILED, 99, "Insufficient permissions"));

        SyncReport report = executor.execute(SyncPlan.builder()
                .planId("plan-1")
                .action(SyncAction.builder()
                        .actionId("001-update-photo-metadata")
                        .type(SyncActionType.UPDATE_PHOTO_METADATA)
                        .safety(SyncActionSafety.SAFE_AUTOMATIC)
                        .photoId("photo-11")
                        .attribute("externalServiceId", Integer.toString(FLICKR_SERVICE_ID))
                        .attribute("itemInventoryPhotoId", "11")
                        .attribute("externalImageId", "201")
                        .attribute("desiredTitle", "New title")
                        .attribute("desiredDescription", "New description")
                        .build())
                .build(), false);

        assertThat(report.getResults().getFirst())
                .extracting(
                        SyncActionResult::getStatus,
                        SyncActionResult::getAttempts,
                        SyncActionResult::isRetried,
                        SyncActionResult::isRetryable,
                        SyncActionResult::getErrorType
                )
                .containsExactly(
                        SyncActionStatus.FAILED,
                        1,
                        false,
                        false,
                        PhotoServiceErrorType.AUTHORIZATION_FAILED
                );
        verify(imageHostingService).updatePhotoMetadata(any());
    }

    @Test
    void executeSkipsUnsupportedActionTypes() {
        SyncReport report = executor.execute(SyncPlan.builder()
                .planId("plan-1")
                .action(SyncAction.builder()
                        .actionId("001-delete-photo")
                        .type(SyncActionType.DELETE_PHOTO)
                        .build())
                .build(), false);

        assertThat(report.getResults().getFirst().getStatus()).isEqualTo(SyncActionStatus.SKIPPED);
        verify(imageHostingService, never()).deletePhoto(any());
    }

    private static SyncAction uploadAction() {
        return SyncAction.builder()
                .actionId("001-upload-photo")
                .type(SyncActionType.UPLOAD_PHOTO)
                .safety(SyncActionSafety.SAFE_AUTOMATIC)
                .filename("front.jpg")
                .attribute("externalServiceId", Integer.toString(FLICKR_SERVICE_ID))
                .attribute("itemInventoryId", "100")
                .attribute("itemInventoryPhotoId", "11")
                .build();
    }

    private static SyncAction membershipAction() {
        return SyncAction.builder()
                .actionId("001-update-album-membership")
                .type(SyncActionType.UPDATE_ALBUM_MEMBERSHIP)
                .safety(SyncActionSafety.SAFE_AUTOMATIC)
                .albumId("album-100")
                .attribute("externalServiceId", Integer.toString(FLICKR_SERVICE_ID))
                .attribute("itemInventoryId", "100")
                .attribute("externalImageAlbumId", "301")
                .attribute("desiredPhotoIds", "photo-11,photo-12")
                .attribute("desiredPrimaryPhotoId", "photo-11")
                .build();
    }

    private static ItemInventoryPhoto photo(Integer itemInventoryPhotoId, boolean primary) {
        return ItemInventoryPhoto.builder()
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .itemInventoryId(100)
                .s3Bucket("photos")
                .s3Key("100/front.jpg")
                .md5("md5-" + itemInventoryPhotoId)
                .metadataHash("metadata-" + itemInventoryPhotoId)
                .fileName("front.jpg")
                .primary(primary)
                .caption("Front caption")
                .build();
    }

    private static ExternalImageAlbum album() {
        return ExternalImageAlbum.builder()
                .externalImageAlbumId(301L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryId(100)
                .externalAlbumId("album-100")
                .build();
    }

    private static ExternalImage externalImage(Long externalImageId, Integer itemInventoryPhotoId, String externalServiceImageId) {
        return ExternalImage.builder()
                .externalImageId(externalImageId)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .externalServiceImageId(externalServiceImageId)
                .build();
    }

    private static <T> PhotoServiceResponse<T> response(T value) {
        return new TestPhotoServiceResponse<>(value, false, null, null, PhotoServiceErrorType.UNKNOWN);
    }

    private static ImageHostingShortUrlResult shortUrlResult(ImageHostingShortUrlStatus status, String shortUrl) {
        return ImageHostingShortUrlResult.builder()
                .status(status)
                .shortUrl(shortUrl)
                .message(status.name())
                .build();
    }

    private static <T> PhotoServiceResponse<T> errorResponse(
            PhotoServiceErrorType errorType,
            Integer responseCode,
            String responseMessage
    ) {
        return new TestPhotoServiceResponse<>(null, true, responseCode, responseMessage, errorType);
    }

    private record TestPhotoServiceResponse<T>(
            T value,
            boolean error,
            Integer responseCode,
            String responseMessage,
            PhotoServiceErrorType errorType
    ) implements PhotoServiceResponse<T> {

        @Override
        public T get() {
            return value;
        }

        @Override
        public void accept(T value) {
        }

        @Override
        public boolean isError() {
            return error;
        }
    }
}
