package io.legohunter.egress.imagehosting;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ExternalImageAlbumImageDao;
import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dao.ItemInventoryExternalCatalogItemDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventoryExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.enums.ExternalSyncStatus;
import io.legohunter.egress.imagehosting.description.GeneratedDescriptionComposer;
import io.legohunter.egress.imagehosting.preflight.ImageHostingPreflightIssue;
import io.legohunter.egress.imagehosting.preflight.ImageHostingPreflightIssueType;
import io.legohunter.egress.imagehosting.preflight.ImageHostingPreflightResult;
import io.legohunter.egress.imagehosting.preflight.ImageHostingPreflightValidator;
import io.legohunter.egress.imagehosting.publishing.ImageHostingPublishingPolicy;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingPhoto;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateReader;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateSnapshot;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumMembershipRequest;
import io.legohunter.imaging.model.HostedAlbumMetadataUpdate;
import io.legohunter.imaging.model.HostedPhotoMetadataUpdate;
import io.legohunter.imaging.model.HostedAlbumCreateRequest;
import io.legohunter.imaging.model.PhotoServiceErrorType;
import io.legohunter.imaging.model.PhotoServiceRequest;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import io.legohunter.ingress.s3.api.S3Service;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.legohunter.data.enums.ExternalSyncStatus.SYNCED;
import static io.legohunter.egress.imagehosting.ImageHostingSyncOutcome.DRY_RUN;
import static io.legohunter.egress.imagehosting.ImageHostingSyncOutcome.FAILED;
import static io.legohunter.egress.imagehosting.ImageHostingSyncOutcome.PARTIAL_FAILURE;
import static io.legohunter.egress.imagehosting.ImageHostingSyncOutcome.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultImageHostingSyncServiceTest {
    private static final int FLICKR_SERVICE_ID = 10;

    @Mock
    private ImageHostingService imageHostingService;

    @Mock
    private S3Service s3Service;

    @Mock
    private ItemInventoryDao itemInventoryDao;

    @Mock
    private ItemInventoryPhotoDao itemInventoryPhotoDao;

    @Mock
    private ExternalCatalogItemDao externalCatalogItemDao;

    @Mock
    private ItemInventoryExternalCatalogItemDao itemInventoryExternalCatalogItemDao;

    @Mock
    private ExternalImageDao externalImageDao;

    @Mock
    private ExternalImageAlbumDao externalImageAlbumDao;

    @Mock
    private ExternalImageAlbumImageDao externalImageAlbumImageDao;

    @Mock
    private ImageHostingDesiredStateReader desiredStateReader;

    @Mock
    private ImageHostingPreflightValidator preflightValidator;

    @TempDir
    private Path tempDirectory;

    private SimpleMeterRegistry meterRegistry;
    private DefaultImageHostingSyncService service;

    @BeforeEach
    void setUp() {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.getSync().setExternalServiceId(FLICKR_SERVICE_ID);
        properties.getSync().setTempDirectory(tempDirectory);
        properties.getSync().getRetry().setInitialBackoffMs(0L);
        ImageHostingSyncProperties.Provider flickr = new ImageHostingSyncProperties.Provider();
        flickr.setEnabled(true);
        flickr.setExternalServiceId(FLICKR_SERVICE_ID);
        flickr.setDisplayName("Flickr");
        flickr.setMetricsTag("flickr");
        properties.getProviders().put("flickr", flickr);
        meterRegistry = new SimpleMeterRegistry();

        service = new DefaultImageHostingSyncService(
                Optional.of(imageHostingService),
                s3Service,
                itemInventoryDao,
                itemInventoryPhotoDao,
                externalCatalogItemDao,
                itemInventoryExternalCatalogItemDao,
                externalImageDao,
                externalImageAlbumDao,
                externalImageAlbumImageDao,
                properties,
                new ImageHostingSyncMetricsService(meterRegistry),
                desiredStateReader,
                preflightValidator,
                new ImageHostingRetryTemplate(properties),
                new ImageHostingPublishingPolicy(properties),
                new GeneratedDescriptionComposer(
                        itemInventoryDao,
                        itemInventoryPhotoDao,
                        externalCatalogItemDao,
                        itemInventoryExternalCatalogItemDao,
                        externalImageAlbumDao,
                        properties
                )
        );

        lenient().when(desiredStateReader.read(any())).thenReturn(validSnapshot());
        lenient().when(preflightValidator.validate(any(), any())).thenReturn(ImageHostingPreflightResult.valid());
    }

    @Test
    void syncItemInventory_uploadsPhotoCreatesAlbumAndPersistsMembership() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImageAlbum album = album(null);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.empty());
        when(s3Service.getObject("photos", "100/front.jpg"))
                .thenReturn(new ByteArrayInputStream("image".getBytes()));
        when(imageHostingService.uploadPhoto(any())).thenReturn(response("flickr-photo-11"));
        when(externalImageDao.upsert(any())).thenAnswer(invocation -> {
            ExternalImage externalImage = invocation.getArgument(0);
            externalImage.setExternalImageId(201L);
            return externalImage;
        });
        when(imageHostingService.createAlbum(any())).thenReturn(response(HostedAlbum.builder()
                .id("flickr-album-100")
                .url("https://flickr.example/albums/100")
                .build()));
        when(imageHostingService.updateAlbumMembership(any())).thenReturn(response(null));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getPhotosDiscovered()).isEqualTo(1);
        assertThat(result.getPhotosUploaded()).isEqualTo(1);
        assertThat(result.getUploadedPhotoIds()).containsExactly(11);
        assertThat(result.getProvider()).isEqualTo("flickr");
        assertThat(result.getExternalImageAlbumId()).isEqualTo(301L);
        assertThat(result.getAlbumId()).isEqualTo("flickr-album-100");
        assertThat(result.getAlbumUrl()).isEqualTo("https://flickr.example/albums/100");
        assertThat(result.isAlbumCreated()).isTrue();
        assertThat(result.isMembershipUpdated()).isTrue();
        assertThat(meterRegistry.counter(
                "image_hosting_sync",
                "provider", "flickr",
                "outcome", "success",
                "dry_run", "false"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "image_hosting_photo_upload",
                "provider", "flickr",
                "result", "uploaded"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "image_hosting_album_operation",
                "provider", "flickr",
                "operation", "create",
                "result", "success"
        ).count()).isEqualTo(1.0);

        ArgumentCaptor<ExternalImage> imageCaptor = ArgumentCaptor.forClass(ExternalImage.class);
        verify(externalImageDao).upsert(imageCaptor.capture());
        assertThat(imageCaptor.getValue())
                .extracting(
                        ExternalImage::getExternalServiceId,
                        ExternalImage::getItemInventoryPhotoId,
                        ExternalImage::getExternalServiceImageId,
                        ExternalImage::getMd5AtUpload,
                        ExternalImage::getMetadataHashAtSync,
                        ExternalImage::getSyncStatus
                )
                .containsExactly(FLICKR_SERVICE_ID, 11, "flickr-photo-11", "md5-11", "metadata-11", SYNCED);

        ArgumentCaptor<PhotoServiceRequest<HostedAlbumMembershipRequest>> membershipCaptor =
                ArgumentCaptor.forClass(PhotoServiceRequest.class);
        verify(imageHostingService).updateAlbumMembership(membershipCaptor.capture());
        HostedAlbumMembershipRequest membershipRequest = membershipCaptor.getValue().get();
        assertThat(membershipRequest.getAlbumId()).isEqualTo("flickr-album-100");
        assertThat(membershipRequest.getPrimaryPhotoId()).isEqualTo("flickr-photo-11");
        assertThat(membershipRequest.getPhotoIds()).containsExactly("flickr-photo-11");

        ArgumentCaptor<ExternalImageAlbumImage> membershipRowCaptor =
                ArgumentCaptor.forClass(ExternalImageAlbumImage.class);
        verify(externalImageAlbumImageDao).upsert(membershipRowCaptor.capture());
        assertThat(membershipRowCaptor.getValue())
                .extracting(
                        ExternalImageAlbumImage::getExternalImageAlbumId,
                        ExternalImageAlbumImage::getExternalImageId,
                        ExternalImageAlbumImage::getSortOrder,
                        ExternalImageAlbumImage::getPrimary
                )
                .containsExactly(301L, 201L, 1, true);
    }

    @Test
    void syncItemInventory_usesBricklinkExternalCatalogItemForCreatedAlbumTitle() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImageAlbum album = album(null);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(itemInventoryExternalCatalogItemDao.findByItemInventoryId(100)).thenReturn(Set.of(ItemInventoryExternalCatalogItem.builder()
                .externalCatalogItemId(501)
                .itemInventoryId(100)
                .build()));
        when(externalCatalogItemDao.findByExternalCatalogItemId(501)).thenReturn(Optional.of(externalCatalogItem(501, 2, "4558-1", "Metroliner")));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.empty());
        when(s3Service.getObject("photos", "100/front.jpg"))
                .thenReturn(new ByteArrayInputStream("image".getBytes()));
        when(imageHostingService.uploadPhoto(any())).thenReturn(response("flickr-photo-11"));
        when(externalImageDao.upsert(any())).thenAnswer(invocation -> {
            ExternalImage externalImage = invocation.getArgument(0);
            externalImage.setExternalImageId(201L);
            return externalImage;
        });
        when(imageHostingService.createAlbum(any())).thenReturn(response(HostedAlbum.builder()
                .id("flickr-album-100")
                .url("https://flickr.example/albums/100")
                .build()));
        when(imageHostingService.updateAlbumMembership(any())).thenReturn(response(null));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(SUCCESS);
        ArgumentCaptor<ExternalImageAlbum> albumCaptor = ArgumentCaptor.forClass(ExternalImageAlbum.class);
        verify(externalImageAlbumDao).findOrCreateForItem(albumCaptor.capture());
        assertThat(albumCaptor.getValue().getTitle()).isEqualTo("4558-1 - Metroliner");

        ArgumentCaptor<PhotoServiceRequest<HostedAlbumCreateRequest>> createRequestCaptor = ArgumentCaptor.forClass(PhotoServiceRequest.class);
        verify(imageHostingService).createAlbum(createRequestCaptor.capture());
        assertThat(createRequestCaptor.getValue().get().getTitle()).isEqualTo("4558-1 - Metroliner");
    }

    @Test
    void syncItemInventory_reusesExistingExternalImageWithoutUploadingAgain() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImage existingImage = ExternalImage.builder()
                .externalImageId(201L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryPhotoId(11)
                .externalServiceImageId("flickr-photo-11")
                .title("front.jpg")
                .md5AtUpload("md5-11")
                .metadataHashAtSync("metadata-11")
                .syncStatus(SYNCED)
                .build();
        ExternalImageAlbum album = album("flickr-album-100");

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.of(existingImage));
        when(imageHostingService.updateAlbumMembership(any())).thenReturn(response(null));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getPhotosSkipped()).isEqualTo(1);
        assertThat(result.getSkippedPhotoIds()).containsExactly(11);
        assertThat(result.getPhotosUploaded()).isZero();
        assertThat(result.getAlbumId()).isEqualTo("flickr-album-100");
        assertThat(result.isAlbumCreated()).isFalse();
        assertThat(result.isMembershipUpdated()).isTrue();

        verify(imageHostingService, never()).uploadPhoto(any());
        verify(imageHostingService, never()).createAlbum(any());
        verify(externalImageDao, never()).upsert(any());
    }

    @Test
    void syncItemInventory_recreatesMissingHostedAlbumWhenMembershipUpdateReportsAlbumNotFound() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImage existingImage = ExternalImage.builder()
                .externalImageId(201L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryPhotoId(11)
                .externalServiceImageId("flickr-photo-11")
                .title("front.jpg")
                .md5AtUpload("md5-11")
                .metadataHashAtSync("metadata-11")
                .syncStatus(SYNCED)
                .build();
        ExternalImageAlbum album = album("stale-flickr-album");

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.of(existingImage));
        when(imageHostingService.updateAlbumMembership(any()))
                .thenReturn(errorResponse(PhotoServiceErrorType.ALBUM_NOT_FOUND, 1, "Photoset not found"))
                .thenReturn(response(null));
        when(imageHostingService.createAlbum(any())).thenReturn(response(HostedAlbum.builder()
                .id("replacement-flickr-album")
                .url("https://flickr.example/albums/replacement")
                .build()));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getPhotosSkipped()).isEqualTo(1);
        assertThat(result.isAlbumCreated()).isTrue();
        assertThat(result.isMembershipUpdated()).isTrue();
        assertThat(result.getAlbumId()).isEqualTo("replacement-flickr-album");
        assertThat(result.getAlbumUrl()).isEqualTo("https://flickr.example/albums/replacement");
        assertThat(result.getFailureMessages()).isEmpty();
        assertThat(album.getExternalAlbumId()).isEqualTo("replacement-flickr-album");

        verify(imageHostingService).createAlbum(any());
        ArgumentCaptor<PhotoServiceRequest<HostedAlbumMembershipRequest>> membershipCaptor =
                ArgumentCaptor.forClass(PhotoServiceRequest.class);
        verify(imageHostingService, times(2)).updateAlbumMembership(membershipCaptor.capture());
        assertThat(membershipCaptor.getAllValues())
                .extracting(request -> request.get().getAlbumId())
                .containsExactly("stale-flickr-album", "replacement-flickr-album");
    }

    @Test
    void syncItemInventory_recreatesMissingHostedAlbumWhenMetadataUpdateReportsAlbumNotFound() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImage existingImage = ExternalImage.builder()
                .externalImageId(201L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryPhotoId(11)
                .externalServiceImageId("flickr-photo-11")
                .title("front.jpg")
                .md5AtUpload("md5-11")
                .metadataHashAtSync("metadata-11")
                .syncStatus(SYNCED)
                .build();
        ExternalImageAlbum album = album("stale-flickr-album");
        album.setTitle("Old hosted title");

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(itemInventoryExternalCatalogItemDao.findByItemInventoryId(100)).thenReturn(Set.of(ItemInventoryExternalCatalogItem.builder()
                .externalCatalogItemId(501)
                .itemInventoryId(100)
                .build()));
        when(externalCatalogItemDao.findByExternalCatalogItemId(501)).thenReturn(Optional.of(externalCatalogItem(501, 2, "4558-1", "Metroliner")));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.of(existingImage));
        when(imageHostingService.updateAlbumMetadata(any()))
                .thenReturn(errorResponse(PhotoServiceErrorType.ALBUM_NOT_FOUND, 1, "Photoset not found"));
        when(imageHostingService.createAlbum(any())).thenReturn(response(HostedAlbum.builder()
                .id("replacement-flickr-album")
                .url("https://flickr.example/albums/replacement")
                .build()));
        when(imageHostingService.updateAlbumMembership(any())).thenReturn(response(null));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.isAlbumCreated()).isTrue();
        assertThat(result.isMembershipUpdated()).isTrue();
        assertThat(result.getAlbumId()).isEqualTo("replacement-flickr-album");
        assertThat(result.getFailureMessages()).isEmpty();

        verify(imageHostingService).updateAlbumMetadata(any());
        verify(imageHostingService).createAlbum(any());
        ArgumentCaptor<PhotoServiceRequest<HostedAlbumMembershipRequest>> membershipCaptor =
                ArgumentCaptor.forClass(PhotoServiceRequest.class);
        verify(imageHostingService).updateAlbumMembership(membershipCaptor.capture());
        assertThat(membershipCaptor.getValue().get().getAlbumId()).isEqualTo("replacement-flickr-album");
    }

    @Test
    void syncItemInventory_updatesExistingHostedAlbumTitleWhenExternalCatalogItemTitleDiffers() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImage existingImage = ExternalImage.builder()
                .externalImageId(201L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryPhotoId(11)
                .externalServiceImageId("flickr-photo-11")
                .title("front.jpg")
                .md5AtUpload("md5-11")
                .metadataHashAtSync("metadata-11")
                .syncStatus(SYNCED)
                .build();
        ExternalImageAlbum album = album("flickr-album-100");
        album.setTitle("Box: Good, Instructions: Excellent&nbsp;</br>[(5) Photos]");

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(itemInventoryExternalCatalogItemDao.findByItemInventoryId(100)).thenReturn(Set.of(ItemInventoryExternalCatalogItem.builder()
                .externalCatalogItemId(501)
                .itemInventoryId(100)
                .build()));
        when(externalCatalogItemDao.findByExternalCatalogItemId(501)).thenReturn(Optional.of(externalCatalogItem(501, 2, "4558-1", "Metroliner")));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.of(existingImage));
        when(imageHostingService.updateAlbumMetadata(any())).thenReturn(response(null));
        when(imageHostingService.updateAlbumMembership(any())).thenReturn(response(null));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(SUCCESS);
        ArgumentCaptor<PhotoServiceRequest<HostedAlbumMetadataUpdate>> metadataCaptor =
                ArgumentCaptor.forClass(PhotoServiceRequest.class);
        verify(imageHostingService).updateAlbumMetadata(metadataCaptor.capture());
        assertThat(metadataCaptor.getValue().get())
                .extracting(
                        HostedAlbumMetadataUpdate::getAlbumId,
                        HostedAlbumMetadataUpdate::getTitle,
                        HostedAlbumMetadataUpdate::getDescription
                )
                .containsExactly("flickr-album-100", "4558-1 - Metroliner", "4558-1 - Metroliner.");
        verify(externalImageAlbumDao, atLeastOnce()).update(album);
        assertThat(album.getTitle()).isEqualTo("4558-1 - Metroliner");
    }

    @Test
    void syncItemInventory_updatesHostedMetadataWhenMetadataHashChanges() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        photo.setCaption("Updated caption");
        ExternalImage existingImage = ExternalImage.builder()
                .externalImageId(201L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryPhotoId(11)
                .externalServiceImageId("flickr-photo-11")
                .title("Old caption")
                .md5AtUpload("old-md5")
                .metadataHashAtSync("old-metadata")
                .syncStatus(SYNCED)
                .build();
        ExternalImageAlbum album = album("flickr-album-100");

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.of(existingImage));
        when(imageHostingService.updatePhotoMetadata(any())).thenReturn(response(null));
        when(externalImageDao.upsert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(imageHostingService.updateAlbumMembership(any())).thenReturn(response(null));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getPhotosUploaded()).isZero();
        assertThat(result.getPhotosMetadataUpdated()).isEqualTo(1);
        assertThat(result.getMetadataUpdatedPhotoIds()).containsExactly(11);
        assertThat(result.getPhotosSkipped()).isZero();

        ArgumentCaptor<PhotoServiceRequest<HostedPhotoMetadataUpdate>> metadataCaptor =
                ArgumentCaptor.forClass(PhotoServiceRequest.class);
        verify(imageHostingService).updatePhotoMetadata(metadataCaptor.capture());
        assertThat(metadataCaptor.getValue().get())
                .extracting(
                        HostedPhotoMetadataUpdate::getPhotoId,
                        HostedPhotoMetadataUpdate::getTitle,
                        HostedPhotoMetadataUpdate::getDescription
                )
                .containsExactly("flickr-photo-11", "Updated caption", "Updated caption");

        ArgumentCaptor<ExternalImage> imageCaptor = ArgumentCaptor.forClass(ExternalImage.class);
        verify(externalImageDao).upsert(imageCaptor.capture());
        assertThat(imageCaptor.getValue())
                .extracting(
                        ExternalImage::getMd5AtUpload,
                        ExternalImage::getMetadataHashAtSync,
                        ExternalImage::getTitle,
                        ExternalImage::getSyncStatus
                )
                .containsExactly("md5-11", "metadata-11", "Updated caption", SYNCED);
        verify(imageHostingService, never()).uploadPhoto(any());
    }

    @Test
    void syncItemInventory_mapsUploadProviderErrorToFailedExternalImage() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImageAlbum album = album(null);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.empty());
        when(s3Service.getObject("photos", "100/front.jpg"))
                .thenReturn(new ByteArrayInputStream("image".getBytes()));
        when(imageHostingService.uploadPhoto(any())).thenReturn(errorResponse(99, "provider rejected upload"));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(FAILED);
        assertThat(result.getPhotosFailed()).isEqualTo(1);
        assertThat(result.getFailedPhotoIds()).containsExactly(11);
        assertThat(result.getFailureMessages()).containsExactly("Photo [11] failed: provider rejected upload");
        assertThat(result.isAlbumCreated()).isFalse();
        assertThat(result.isMembershipUpdated()).isFalse();

        ArgumentCaptor<ExternalImage> imageCaptor = ArgumentCaptor.forClass(ExternalImage.class);
        verify(externalImageDao).upsert(imageCaptor.capture());
        assertThat(imageCaptor.getValue())
                .extracting(ExternalImage::getSyncStatus, ExternalImage::getErrorMessage)
                .containsExactly(ExternalSyncStatus.FAILED, "provider rejected upload");

        verify(imageHostingService, never()).createAlbum(any());
        verify(imageHostingService, never()).updateAlbumMembership(any());
        verify(externalImageAlbumDao).update(album);
        assertThat(album.getSyncStatus()).isEqualTo(ExternalSyncStatus.FAILED);
    }

    @Test
    void syncItemInventory_retriesTransientUploadFailureBeforeSucceeding() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImageAlbum album = album(null);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.empty());
        when(s3Service.getObject("photos", "100/front.jpg"))
                .thenReturn(new ByteArrayInputStream("image".getBytes()));
        when(imageHostingService.uploadPhoto(any()))
                .thenReturn(errorResponse(PhotoServiceErrorType.SERVICE_UNAVAILABLE, 503, "Flickr unavailable"))
                .thenReturn(response("flickr-photo-11"));
        when(externalImageDao.upsert(any())).thenAnswer(invocation -> {
            ExternalImage externalImage = invocation.getArgument(0);
            externalImage.setExternalImageId(201L);
            return externalImage;
        });
        when(imageHostingService.createAlbum(any())).thenReturn(response(HostedAlbum.builder()
                .id("flickr-album-100")
                .url("https://flickr.example/albums/100")
                .build()));
        when(imageHostingService.updateAlbumMembership(any())).thenReturn(response(null));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getPhotosUploaded()).isEqualTo(1);
        verify(imageHostingService, times(2)).uploadPhoto(any());
    }

    @Test
    void sync_skipsPreviouslyFailedImageUnlessRetryFailedIsRequested() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImage failedImage = ExternalImage.builder()
                .externalImageId(201L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryPhotoId(11)
                .syncStatus(ExternalSyncStatus.FAILED)
                .errorMessage("previous failure")
                .build();
        ExternalImageAlbum album = album(null);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.of(failedImage));

        ImageHostingSyncResult result = service.sync(ImageHostingSyncRequest.builder()
                .itemInventoryId(100)
                .retryFailed(false)
                .build());

        assertThat(result.getOutcome()).isEqualTo(FAILED);
        assertThat(result.getPhotosFailed()).isEqualTo(1);
        assertThat(result.getFailureMessages())
                .containsExactly("Photo [11] failed: Previous failed image sync exists and retryFailed=false");

        verifyNoInteractions(s3Service);
        verify(imageHostingService, never()).uploadPhoto(any());
        verify(externalImageDao, never()).upsert(any());
        verify(externalImageAlbumDao).update(album);
    }

    @Test
    void sync_retriesPreviouslyFailedImageWhenRetryFailedIsRequested() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImage failedImage = ExternalImage.builder()
                .externalImageId(201L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryPhotoId(11)
                .syncStatus(ExternalSyncStatus.FAILED)
                .errorMessage("previous failure")
                .build();
        ExternalImageAlbum album = album(null);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.of(failedImage));
        when(s3Service.getObject("photos", "100/front.jpg"))
                .thenReturn(new ByteArrayInputStream("image".getBytes()));
        when(imageHostingService.uploadPhoto(any())).thenReturn(response("flickr-photo-11"));
        when(externalImageDao.upsert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(imageHostingService.createAlbum(any())).thenReturn(response(HostedAlbum.builder()
                .id("flickr-album-100")
                .url("https://flickr.example/albums/100")
                .build()));
        when(imageHostingService.updateAlbumMembership(any())).thenReturn(response(null));

        ImageHostingSyncResult result = service.sync(ImageHostingSyncRequest.builder()
                .itemInventoryId(100)
                .retryFailed(true)
                .build());

        assertThat(result.getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.isRetryFailed()).isTrue();
        assertThat(result.getPhotosUploaded()).isEqualTo(1);
        assertThat(result.getUploadedPhotoIds()).containsExactly(11);

        verify(imageHostingService).uploadPhoto(any());
        verify(externalImageDao).upsert(failedImage);
    }

    @Test
    void syncItemInventory_reportsPartialFailureWhenAlbumCreationFailsAfterUpload() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImageAlbum album = album(null);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.empty());
        when(s3Service.getObject("photos", "100/front.jpg"))
                .thenReturn(new ByteArrayInputStream("image".getBytes()));
        when(imageHostingService.uploadPhoto(any())).thenReturn(response("flickr-photo-11"));
        when(externalImageDao.upsert(any())).thenAnswer(invocation -> {
            ExternalImage externalImage = invocation.getArgument(0);
            externalImage.setExternalImageId(201L);
            return externalImage;
        });
        when(imageHostingService.createAlbum(any())).thenReturn(errorResponse(98, "provider rejected album"));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(PARTIAL_FAILURE);
        assertThat(result.getPhotosUploaded()).isEqualTo(1);
        assertThat(result.getFailureMessages()).containsExactly("provider rejected album");
        assertThat(result.isAlbumCreated()).isFalse();
        assertThat(result.isMembershipUpdated()).isFalse();

        verify(imageHostingService, never()).updateAlbumMembership(any());
    }

    @Test
    void syncItemInventory_noPhotosDoesNotTouchProviderOrExternalAlbumTables() {
        ItemInventory inventory = inventory();

        when(desiredStateReader.read(any())).thenReturn(ImageHostingDesiredStateSnapshot.builder()
                .provider("flickr")
                .externalServiceId(FLICKR_SERVICE_ID)
                .inventory(inventory)
                .build());
        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of());

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getPhotosDiscovered()).isZero();
        assertThat(result.getPhotosUploaded()).isZero();
        assertThat(result.isAlbumCreated()).isFalse();
        assertThat(result.isMembershipUpdated()).isFalse();

        verifyNoInteractions(imageHostingService, externalImageDao, externalImageAlbumDao, externalImageAlbumImageDao);
    }

    @Test
    void sync_dryRunDiscoversPhotosWithoutProviderCallsOrExternalImageWrites() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));

        ImageHostingSyncResult result = service.sync(ImageHostingSyncRequest.builder()
                .itemInventoryId(100)
                .dryRun(true)
                .build());

        assertThat(result.isDryRun()).isTrue();
        assertThat(result.getOutcome()).isEqualTo(DRY_RUN);
        assertThat(result.getPhotosDiscovered()).isEqualTo(1);
        assertThat(result.getPhotosUploaded()).isZero();
        assertThat(result.isAlbumCreated()).isFalse();
        assertThat(result.isMembershipUpdated()).isFalse();

        verifyNoInteractions(imageHostingService, s3Service, externalImageDao, externalImageAlbumDao, externalImageAlbumImageDao);
    }

    @Test
    void sync_stopsBeforeProviderCallsWhenDbS3PreflightFails() {
        ImageHostingPreflightIssue issue = ImageHostingPreflightIssue.builder()
                .type(ImageHostingPreflightIssueType.S3_OBJECT_MISSING)
                .itemInventoryId(100)
                .itemInventoryPhotoId(11)
                .message("S3 object [photos/100/front.jpg] is not available for inventory photo [11]")
                .build();
        when(preflightValidator.validate(any(), any()))
                .thenReturn(ImageHostingPreflightResult.builder().issue(issue).build());

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(FAILED);
        assertThat(result.getPhotosDiscovered()).isEqualTo(1);
        assertThat(result.getPhotosFailed()).isEqualTo(1);
        assertThat(result.getFailedPhotoIds()).containsExactly(11);
        assertThat(result.getFailureMessages())
                .containsExactly("Photo [11] failed: Preflight failed: S3 object [photos/100/front.jpg] is not available for inventory photo [11]");

        verifyNoInteractions(imageHostingService, s3Service, itemInventoryDao, itemInventoryPhotoDao);
        verifyNoInteractions(externalImageDao, externalImageAlbumDao, externalImageAlbumImageDao);
    }

    private static ItemInventory inventory() {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(100);
        inventory.setUuid("inventory-uuid");
        inventory.setDescription("Inventory album");
        return inventory;
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
                .build();
    }

    private static ImageHostingDesiredStateSnapshot validSnapshot() {
        return ImageHostingDesiredStateSnapshot.builder()
                .provider("flickr")
                .externalServiceId(FLICKR_SERVICE_ID)
                .inventory(inventory())
                .photo(DesiredImageHostingPhoto.builder()
                        .inventoryPhoto(photo(11, true))
                        .build())
                .build();
    }

    private static ExternalImageAlbum album(String externalAlbumId) {
        return ExternalImageAlbum.builder()
                .externalImageAlbumId(301L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryId(100)
                .externalAlbumId(externalAlbumId)
                .title("Inventory album")
                .syncStatus(ExternalSyncStatus.PENDING)
                .build();
    }

    private static ExternalCatalogItem externalCatalogItem(Integer externalCatalogItemId, Integer serviceId, String externalNumber, String name) {
        ExternalCatalogItem externalCatalogItem = new ExternalCatalogItem();
        externalCatalogItem.setExternalCatalogItemId(externalCatalogItemId);
        externalCatalogItem.setExternalServiceId(serviceId);
        externalCatalogItem.setExternalItemKey(externalNumber);
        externalCatalogItem.setItemName(name);
        return externalCatalogItem;
    }

    private static <T> PhotoServiceResponse<T> response(T value) {
        return new TestPhotoServiceResponse<>(value, false, null, null, PhotoServiceErrorType.UNKNOWN);
    }

    private static <T> PhotoServiceResponse<T> errorResponse(Integer responseCode, String responseMessage) {
        return errorResponse(PhotoServiceErrorType.UNKNOWN, responseCode, responseMessage);
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
