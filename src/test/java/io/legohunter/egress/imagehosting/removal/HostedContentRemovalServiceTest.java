package io.legohunter.egress.imagehosting.removal;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ExternalImageAlbumImageDao;
import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.enums.ExternalSyncStatus;
import io.legohunter.egress.imagehosting.ImageHostingRetryTemplate;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.egress.imagehosting.plan.ImageHostingSyncPlanExecutor;
import io.legohunter.egress.imagehosting.plan.ImageHostingSyncPlanService;
import io.legohunter.imaging.model.PhotoServiceErrorType;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import io.legohunter.imaging.service.sync.model.SyncAction;
import io.legohunter.imaging.service.sync.model.SyncActionSafety;
import io.legohunter.imaging.service.sync.model.SyncActionType;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.ingress.s3.api.S3Service;
import io.legohunter.lego.data.environment.LegoEnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HostedContentRemovalServiceTest {
    private static final int FLICKR = 10;
    private static final int ITEM = 100;

    @Mock
    private ImageHostingService imageHostingService;
    @Mock
    private ItemInventoryPhotoDao itemInventoryPhotoDao;
    @Mock
    private ExternalImageDao externalImageDao;
    @Mock
    private ExternalImageAlbumDao externalImageAlbumDao;
    @Mock
    private ExternalImageAlbumImageDao externalImageAlbumImageDao;
    @Mock
    private ImageHostingSyncPlanService syncPlanService;
    @Mock
    private ImageHostingSyncPlanExecutor syncPlanExecutor;
    @Mock
    private S3Service s3Service;

    private LegoEnvironmentProperties environment;
    private HostedContentRemovalService service;

    @BeforeEach
    void setUp() {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.getSync().getRetry().setInitialBackoffMs(0L);
        environment = new LegoEnvironmentProperties();
        environment.setCode("sandbox");
        service = new HostedContentRemovalService(
                Optional.of(imageHostingService),
                itemInventoryPhotoDao,
                externalImageDao,
                externalImageAlbumDao,
                externalImageAlbumImageDao,
                properties,
                new ImageHostingRetryTemplate(properties),
                syncPlanService,
                syncPlanExecutor,
                s3Service,
                environment
        );
    }

    @Test
    void unpublishPhotoDeletesTheHostedCopyThenItsLinkRows() {
        when(externalImageDao.findByItemInventoryPhotoId(11)).thenReturn(Set.of(image(201L, 11, "flickr-11")));
        when(imageHostingService.deletePhoto(any())).thenReturn(ok());

        List<String> removed = service.unpublishPhoto(photo(11));

        assertThat(removed).containsExactly("flickr-11");
        InOrder order = inOrder(imageHostingService, externalImageAlbumImageDao, externalImageDao);
        order.verify(imageHostingService).deletePhoto(any());
        order.verify(externalImageAlbumImageDao).deleteByExternalImageId(201L);
        order.verify(externalImageDao).deleteByExternalImageId(201L);
    }

    @Test
    void unpublishPhotoTreatsACopyTheHostNoLongerHasAsDeleted() {
        when(externalImageDao.findByItemInventoryPhotoId(11)).thenReturn(Set.of(image(201L, 11, "flickr-11")));
        when(imageHostingService.deletePhoto(any()))
                .thenReturn(error(PhotoServiceErrorType.PHOTO_NOT_FOUND, "Photo not found"));

        assertThat(service.unpublishPhoto(photo(11))).containsExactly("flickr-11");

        verify(externalImageDao).deleteByExternalImageId(201L);
    }

    @Test
    void unpublishPhotoKeepsEveryRowWhenTheHostRefuses() {
        when(externalImageDao.findByItemInventoryPhotoId(11)).thenReturn(Set.of(image(201L, 11, "flickr-11")));
        when(imageHostingService.deletePhoto(any()))
                .thenReturn(error(PhotoServiceErrorType.AUTHORIZATION_FAILED, "Insufficient permissions"));

        assertThatThrownBy(() -> service.unpublishPhoto(photo(11)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flickr-11")
                .hasMessageContaining("Insufficient permissions");

        verify(externalImageAlbumImageDao, never()).deleteByExternalImageId(anyLong());
        verify(externalImageDao, never()).deleteByExternalImageId(anyLong());
    }

    @Test
    void unpublishPhotoDoesNothingForAPhotoThatWasNeverPublished() {
        when(externalImageDao.findByItemInventoryPhotoId(11)).thenReturn(Set.of());

        assertThat(service.unpublishPhoto(photo(11))).isEmpty();

        verifyNoInteractions(imageHostingService);
    }

    @Test
    void reconcileDropsTheAlbumRowsWhenTheItemHasNoPhotosLeft() {
        when(externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(FLICKR, ITEM))
                .thenReturn(Optional.of(album()));
        when(itemInventoryPhotoDao.findByItemInventoryId(ITEM)).thenReturn(Set.of());

        service.reconcileAlbumAfterRemoval(ITEM);

        // The host drops an album itself when its last photo is deleted; only the rows remain to remove.
        InOrder order = inOrder(externalImageAlbumImageDao, externalImageAlbumDao);
        order.verify(externalImageAlbumImageDao).deleteByExternalImageAlbumId(301L);
        order.verify(externalImageAlbumDao).deleteByExternalImageAlbumId(301L);
        verifyNoInteractions(syncPlanService, syncPlanExecutor, imageHostingService);
    }

    @Test
    void reconcileBringsTheAlbumBackInStepWhenPhotosRemain() {
        when(externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(FLICKR, ITEM))
                .thenReturn(Optional.of(album()));
        when(itemInventoryPhotoDao.findByItemInventoryId(ITEM)).thenReturn(Set.of(photo(12)));
        SyncPlan plan = SyncPlan.builder()
                .planId("plan-1")
                .action(SyncAction.builder()
                        .actionId("001-update-album-metadata")
                        .type(SyncActionType.UPDATE_ALBUM_METADATA)
                        .safety(SyncActionSafety.SAFE_AUTOMATIC)
                        .build())
                .build();
        when(syncPlanService.plan(any())).thenReturn(plan);

        service.reconcileAlbumAfterRemoval(ITEM);

        // Review-required actions are never applied on a deletion's behalf.
        verify(syncPlanExecutor).execute(plan, false);
        verify(externalImageAlbumDao, never()).deleteByExternalImageAlbumId(anyLong());
    }

    @Test
    void reconcileMarksTheAlbumFailedWhenItCannotBeBroughtBackInStep() {
        ExternalImageAlbum album = album();
        when(externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(FLICKR, ITEM)).thenReturn(Optional.of(album));
        when(itemInventoryPhotoDao.findByItemInventoryId(ITEM)).thenReturn(Set.of(photo(12)));
        when(syncPlanService.plan(any())).thenThrow(new IllegalStateException("Flickr unavailable"));

        service.reconcileAlbumAfterRemoval(ITEM);

        // FAILED is what the scheduled job retries.
        assertThat(album.getSyncStatus()).isEqualTo(ExternalSyncStatus.FAILED);
        assertThat(album.getErrorMessage()).contains("Flickr unavailable");
        verify(externalImageAlbumDao).update(album);
    }

    @Test
    void purgePreviewListsWhatWouldGoAndChangesNothing() {
        ItemInventoryPhoto photo = photo(11);
        when(itemInventoryPhotoDao.findByItemInventoryId(ITEM)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(FLICKR, ITEM))
                .thenReturn(Optional.of(album()));
        when(externalImageDao.findByItemInventoryPhotoId(11)).thenReturn(Set.of(image(201L, 11, "flickr-11")));

        ItemPurgeReport report = service.purgeItem(ITEM, false);

        assertThat(report.applied()).isFalse();
        assertThat(report.environment()).isEqualTo("sandbox");
        assertThat(report.hostedAlbumId()).isEqualTo("flickr-album-1");
        assertThat(report.photos()).singleElement().satisfies(purged -> {
            assertThat(purged.itemInventoryPhotoId()).isEqualTo(11);
            assertThat(purged.hostedPhotoIds()).containsExactly("flickr-11");
            assertThat(purged.bucket()).isEqualTo("lego-photos-sandbox");
            assertThat(purged.key()).isEqualTo("8865-1/uuid/md5-11.jpg");
        });
        verifyNoInteractions(imageHostingService, s3Service);
        verify(itemInventoryPhotoDao, never()).deleteByMd5(anyString());
        verify(externalImageDao, never()).deleteByExternalImageId(anyLong());
        verify(externalImageAlbumDao, never()).deleteByExternalImageAlbumId(anyLong());
    }

    @Test
    void purgeRemovesThePublishedCopyTheRowsTheStoredFileAndThenTheAlbumRows() {
        ItemInventoryPhoto photo = photo(11);
        when(itemInventoryPhotoDao.findByItemInventoryId(ITEM)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(FLICKR, ITEM))
                .thenReturn(Optional.of(album()));
        when(externalImageDao.findByItemInventoryPhotoId(11)).thenReturn(Set.of(image(201L, 11, "flickr-11")));
        when(imageHostingService.deletePhoto(any())).thenReturn(ok());

        ItemPurgeReport report = service.purgeItem(ITEM, true);

        assertThat(report.applied()).isTrue();
        InOrder order = inOrder(imageHostingService, externalImageAlbumImageDao, externalImageDao,
                itemInventoryPhotoDao, s3Service, externalImageAlbumDao);
        order.verify(imageHostingService).deletePhoto(any());
        order.verify(externalImageAlbumImageDao).deleteByExternalImageId(201L);
        order.verify(externalImageDao).deleteByExternalImageId(201L);
        order.verify(itemInventoryPhotoDao).deleteByMd5("md5-11");
        order.verify(s3Service).deleteObject("lego-photos-sandbox", "8865-1/uuid/md5-11.jpg");
        order.verify(externalImageAlbumImageDao).deleteByExternalImageAlbumId(301L);
        order.verify(externalImageAlbumDao).deleteByExternalImageAlbumId(301L);
    }

    @Test
    void purgeCarriesOnWhenAStoredFileCannotBeDeleted() {
        when(itemInventoryPhotoDao.findByItemInventoryId(ITEM)).thenReturn(Set.of(photo(11)));
        when(externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(FLICKR, ITEM)).thenReturn(Optional.empty());
        when(externalImageDao.findByItemInventoryPhotoId(11)).thenReturn(Set.of());
        doThrow(new IllegalStateException("bucket unavailable")).when(s3Service).deleteObject(anyString(), anyString());

        ItemPurgeReport report = service.purgeItem(ITEM, true);

        assertThat(report.photos()).hasSize(1);
        verify(itemInventoryPhotoDao).deleteByMd5("md5-11");
    }

    @Test
    void purgeIsRefusedInProductionEvenAsAPreview() {
        environment.setCode("prod");
        environment.setProduction(true);

        assertThatThrownBy(() -> service.purgeItem(ITEM, false))
                .isInstanceOf(ProductionRemovalRefusedException.class)
                .hasMessageContaining("production");
        assertThatThrownBy(() -> service.purgeItem(ITEM, true))
                .isInstanceOf(ProductionRemovalRefusedException.class);

        verifyNoInteractions(imageHostingService, itemInventoryPhotoDao, externalImageDao, externalImageAlbumDao, s3Service);
    }

    private static ItemInventoryPhoto photo(int id) {
        return ItemInventoryPhoto.builder()
                .itemInventoryPhotoId(id)
                .itemInventoryId(ITEM)
                .md5("md5-" + id)
                .fileName("photo-" + id + ".jpg")
                .s3Bucket("lego-photos-sandbox")
                .s3Key("8865-1/uuid/md5-" + id + ".jpg")
                .build();
    }

    private static ExternalImage image(Long id, int photoId, String hostedPhotoId) {
        return ExternalImage.builder()
                .externalImageId(id)
                .externalServiceId(FLICKR)
                .itemInventoryPhotoId(photoId)
                .externalServiceImageId(hostedPhotoId)
                .build();
    }

    private static ExternalImageAlbum album() {
        return ExternalImageAlbum.builder()
                .externalImageAlbumId(301L)
                .externalServiceId(FLICKR)
                .itemInventoryId(ITEM)
                .externalAlbumId("flickr-album-1")
                .albumUrl("https://flickr.example/albums/flickr-album-1")
                .build();
    }

    private static <T> PhotoServiceResponse<T> ok() {
        return new TestResponse<>(false, null, PhotoServiceErrorType.UNKNOWN);
    }

    private static <T> PhotoServiceResponse<T> error(PhotoServiceErrorType type, String message) {
        return new TestResponse<>(true, message, type);
    }

    private record TestResponse<T>(boolean error, String responseMessage, PhotoServiceErrorType errorType)
            implements PhotoServiceResponse<T> {
        @Override
        public boolean isError() {
            return error;
        }

        @Override
        public Integer responseCode() {
            return error ? 1 : null;
        }

        @Override
        public T get() {
            return null;
        }

        @Override
        public void accept(T value) {
        }
    }
}
