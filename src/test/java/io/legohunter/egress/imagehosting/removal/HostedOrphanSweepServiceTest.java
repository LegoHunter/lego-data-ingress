package io.legohunter.egress.imagehosting.removal;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.egress.imagehosting.ImageHostingRetryTemplate;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumPage;
import io.legohunter.imaging.model.HostedPhoto;
import io.legohunter.imaging.model.HostedPhotoPage;
import io.legohunter.imaging.model.PhotoServiceErrorType;
import io.legohunter.imaging.model.PhotoServiceRequest;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import io.legohunter.lego.data.environment.LegoEnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HostedOrphanSweepServiceTest {
    private static final int FLICKR = 10;

    @Mock
    private ImageHostingService imageHostingService;
    @Mock
    private ExternalImageDao externalImageDao;
    @Mock
    private ExternalImageAlbumDao externalImageAlbumDao;

    private LegoEnvironmentProperties environment;
    private HostedOrphanSweepService service;

    @BeforeEach
    void setUp() {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.getSync().getRetry().setInitialBackoffMs(0L);
        environment = new LegoEnvironmentProperties();
        environment.setCode("sandbox");
        service = new HostedOrphanSweepService(
                Optional.of(imageHostingService),
                externalImageDao,
                externalImageAlbumDao,
                properties,
                new ImageHostingRetryTemplate(properties),
                environment
        );
    }

    /**
     * The account holds: an album an item owns (one known photo, one photo nobody owns), an album nobody owns
     * (one photo), and one photo in no album that nobody owns.
     */
    private void accountWithOrphans() {
        when(imageHostingService.listAlbums(any())).thenReturn(ok(HostedAlbumPage.builder()
                .album(HostedAlbum.builder().id("album-known").title("8865-1 - Test Car").build())
                .album(HostedAlbum.builder().id("album-stray").title("API spike test album").build())
                .page(1).pages(1).build()));
        when(imageHostingService.listAlbumPhotos(any())).thenAnswer(invocation -> {
            PhotoServiceRequest<io.legohunter.imaging.model.HostedAlbumPhotoSearchRequest> request = invocation.getArgument(0);
            return "album-known".equals(request.get().getAlbumId())
                    ? ok(photoPage(photo("photo-known", "front.jpg"), photo("photo-extra", "extra.jpg")))
                    : ok(photoPage(photo("photo-stray", "API spike test photo")));
        });
        when(imageHostingService.listPhotosNotInAlbum(any())).thenReturn(ok(photoPage(photo("photo-loose", "loose.jpg"))));
        lenient().when(externalImageDao.findByExternalServiceIdAndExternalServiceImageId(eq(FLICKR), anyString()))
                .thenReturn(Optional.empty());
        when(externalImageDao.findByExternalServiceIdAndExternalServiceImageId(FLICKR, "photo-known"))
                .thenReturn(Optional.of(ExternalImage.builder().externalImageId(1L).build()));
        when(externalImageAlbumDao.findByExternalServiceIdAndExternalAlbumId(FLICKR, "album-known"))
                .thenReturn(Optional.of(ExternalImageAlbum.builder().externalImageAlbumId(1L).build()));
        when(externalImageAlbumDao.findByExternalServiceIdAndExternalAlbumId(FLICKR, "album-stray"))
                .thenReturn(Optional.empty());
    }

    @Test
    void dryRunListsWhatNoItemOwnsAndDeletesNothing() {
        accountWithOrphans();

        OrphanSweepReport report = service.sweep(false, 0);

        assertThat(report.applied()).isFalse();
        assertThat(report.environment()).isEqualTo("sandbox");
        assertThat(report.albumsSeen()).isEqualTo(2);
        assertThat(report.photosSeen()).isEqualTo(4);
        assertThat(report.orphanPhotos())
                .extracting(OrphanSweepReport.OrphanPhoto::hostedPhotoId, OrphanSweepReport.OrphanPhoto::hostedAlbumId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("photo-extra", "album-known"),
                        org.assertj.core.groups.Tuple.tuple("photo-stray", "album-stray"),
                        org.assertj.core.groups.Tuple.tuple("photo-loose", null)
                );
        assertThat(report.unknownAlbums()).singleElement().satisfies(album -> {
            assertThat(album.hostedAlbumId()).isEqualTo("album-stray");
            assertThat(album.photos()).isEqualTo(1);
            assertThat(album.knownPhotos()).isZero();
        });
        assertThat(report.remaining()).isEqualTo(3);
        assertThat(report.deletedPhotoIds()).isEmpty();
        verify(imageHostingService, never()).deletePhoto(any());
    }

    @Test
    void applyDeletesOnlyTheOrphansAndNeverAPhotoAnItemOwns() {
        accountWithOrphans();
        when(imageHostingService.deletePhoto(any())).thenReturn(ok(null));

        OrphanSweepReport report = service.sweep(true, 200);

        assertThat(report.applied()).isTrue();
        assertThat(report.deletedPhotoIds()).containsExactly("photo-extra", "photo-stray", "photo-loose");
        assertThat(report.remaining()).isZero();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<PhotoServiceRequest<String>> deleted = ArgumentCaptor.forClass(PhotoServiceRequest.class);
        verify(imageHostingService, times(3)).deletePhoto(deleted.capture());
        assertThat(deleted.getAllValues()).extracting(PhotoServiceRequest::get).doesNotContain("photo-known");
    }

    @Test
    void applyStopsAtMaxDeletesAndReportsWhatIsLeft() {
        accountWithOrphans();
        when(imageHostingService.deletePhoto(any())).thenReturn(ok(null));

        OrphanSweepReport report = service.sweep(true, 2);

        assertThat(report.deletedPhotoIds()).containsExactly("photo-extra", "photo-stray");
        assertThat(report.remaining()).isEqualTo(1);
    }

    @Test
    void aFailedDeletionIsReportedAndTheRunContinues() {
        accountWithOrphans();
        when(imageHostingService.deletePhoto(any())).thenAnswer(invocation -> {
            PhotoServiceRequest<String> request = invocation.getArgument(0);
            return "photo-stray".equals(request.get())
                    ? error(PhotoServiceErrorType.AUTHORIZATION_FAILED, "Insufficient permissions")
                    : ok(null);
        });

        OrphanSweepReport report = service.sweep(true, 200);

        assertThat(report.deletedPhotoIds()).containsExactly("photo-extra", "photo-loose");
        assertThat(report.failures()).containsExactly("photo-stray: Insufficient permissions");
        assertThat(report.remaining()).isZero();
    }

    @Test
    void aFailedListingStopsTheSweepBeforeAnythingIsDeleted() {
        // With part of the account unread, photos an item owns could look like orphans.
        when(imageHostingService.listAlbums(any()))
                .thenReturn(error(PhotoServiceErrorType.AUTHENTICATION_FAILED, "Invalid auth token"));

        assertThatThrownBy(() -> service.sweep(true, 200))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("listAlbums")
                .hasMessageContaining("Invalid auth token");

        verify(imageHostingService, never()).deletePhoto(any());
    }

    @Test
    void theSweepIsRefusedInProductionEvenAsADryRun() {
        environment.setCode("prod");
        environment.setProduction(true);

        assertThatThrownBy(() -> service.sweep(false, 0)).isInstanceOf(ProductionRemovalRefusedException.class);
        assertThatThrownBy(() -> service.sweep(true, 200)).isInstanceOf(ProductionRemovalRefusedException.class);

        verifyNoInteractions(imageHostingService, externalImageDao, externalImageAlbumDao);
    }

    private static HostedPhoto photo(String id, String title) {
        return HostedPhoto.builder().id(id).title(title).build();
    }

    private static HostedPhotoPage photoPage(HostedPhoto... photos) {
        return HostedPhotoPage.builder().photos(List.of(photos)).page(1).pages(1).build();
    }

    private static <T> PhotoServiceResponse<T> ok(T value) {
        return new TestResponse<>(value, false, null, PhotoServiceErrorType.UNKNOWN);
    }

    private static <T> PhotoServiceResponse<T> error(PhotoServiceErrorType type, String message) {
        return new TestResponse<>(null, true, message, type);
    }

    private record TestResponse<T>(T value, boolean error, String responseMessage, PhotoServiceErrorType errorType)
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
            return value;
        }

        @Override
        public void accept(T value) {
        }
    }
}
