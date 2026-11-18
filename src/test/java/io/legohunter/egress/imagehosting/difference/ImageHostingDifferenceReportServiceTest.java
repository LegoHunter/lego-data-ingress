package io.legohunter.egress.imagehosting.difference;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.egress.imagehosting.ImageHostingRetryTemplate;
import io.legohunter.egress.imagehosting.ImageHostingSyncMetricsService;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.egress.imagehosting.difference.HostedDifferenceReport.AlbumFindings;
import io.legohunter.egress.imagehosting.difference.HostedDifferenceReport.Finding;
import io.legohunter.egress.imagehosting.headroom.AccountHeadroom;
import io.legohunter.egress.imagehosting.headroom.ImageHostingHeadroomGuard;
import io.legohunter.egress.imagehosting.plan.ImageHostingReconciliationPlanner;
import io.legohunter.egress.imagehosting.publishing.ImageHostingPublishingPolicy;
import io.legohunter.egress.imagehosting.remote.HostedAccountListing;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingAlbum;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingPhoto;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateReader;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateSnapshot;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumPage;
import io.legohunter.imaging.model.HostedPhoto;
import io.legohunter.imaging.model.HostedPhotoPage;
import io.legohunter.imaging.model.PhotoServiceErrorType;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import io.legohunter.lego.data.environment.LegoEnvironmentProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageHostingDifferenceReportServiceTest {
    private static final int FLICKR = 10;
    private static final String TITLE = "8865-1 - Test Car";
    private static final String DESCRIPTION = "Photos: https://bit.ly/x\n\nItem reference: uuid-100";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T08:30:00Z"), ZoneOffset.UTC);
    private static final long TODAY = LocalDate.now(CLOCK).toEpochDay();
    /** With a seven-night turn, an album with this row id is not due for a full read today. */
    private static final long NOT_DUE = TODAY + 1;
    private static final long DUE = TODAY;

    @Mock
    private ExternalImageAlbumDao externalImageAlbumDao;
    @Mock
    private ImageHostingDesiredStateReader desiredStateReader;
    @Mock
    private ImageHostingHeadroomGuard headroomGuard;
    @Mock
    private ImageHostingService imageHostingService;

    private SimpleMeterRegistry meterRegistry;
    private ImageHostingDifferenceReportService service;

    @BeforeEach
    void setUp() {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.getSync().getRetry().setInitialBackoffMs(0L);
        LegoEnvironmentProperties environment = new LegoEnvironmentProperties();
        environment.setCode("sandbox");
        meterRegistry = new SimpleMeterRegistry();
        service = new ImageHostingDifferenceReportService(
                externalImageAlbumDao,
                desiredStateReader,
                new ImageHostingReconciliationPlanner(new ImageHostingPublishingPolicy(properties)),
                new HostedAccountListing(Optional.of(imageHostingService), new ImageHostingRetryTemplate(properties)),
                headroomGuard,
                properties,
                new ImageHostingSyncMetricsService(meterRegistry),
                environment,
                CLOCK
        );
        lenient().when(headroomGuard.current())
                .thenReturn(new AccountHeadroom(true, 40, 1000, true, 100, 900, 860, null));
    }

    @Test
    void albumThatMatchesTheListingIsNotReadFurtherAndHasNoFindings() {
        databaseAlbum(NOT_DUE, 100, "album-1");
        desired(100, "album-1", photo(11, true, "p1"), photo(12, false, "p2"));
        hostAlbums(hostAlbum("album-1", TITLE, DESCRIPTION, "p1", 2));

        HostedDifferenceReport report = service.run();

        assertThat(report.complete()).isTrue();
        assertThat(report.environment()).isEqualTo("sandbox");
        assertThat(report.albumsInDatabase()).isEqualTo(1);
        assertThat(report.albumsChecked()).isEqualTo(1);
        assertThat(report.albumsReadInFull()).isZero();
        assertThat(report.albumsWithDifferences()).isZero();
        assertThat(report.albums()).isEmpty();
        verify(imageHostingService, never()).listAlbumPhotos(any());
        verify(imageHostingService, never()).listPhotosNotInAlbum(any());
        assertThat(service.last()).contains(report);
    }

    @Test
    void descriptionThatDiffersOnlyByTheHostsLinkMarkupIsNotReported() {
        databaseAlbum(NOT_DUE, 100, "album-1");
        desired(100, "album-1", photo(11, true, "p1"));
        hostAlbums(hostAlbum(
                "album-1",
                TITLE,
                "Photos: <a href=\"https://bit.ly/x\" rel=\"noreferrer nofollow\">bit.ly/x</a>\n\nItem reference: uuid-100",
                "p1",
                1
        ));

        HostedDifferenceReport report = service.run();

        assertThat(report.albums()).isEmpty();
        assertThat(report.albumsReadInFull()).isZero();
    }

    @Test
    void albumMissingOnTheHostIsReportedWithHowManyOfItsPhotosAreLeft() {
        databaseAlbum(NOT_DUE, 100, "album-gone");
        desired(100, "album-gone", photo(11, true, "p1"), photo(12, false, "p2"));
        hostAlbums();
        loosePhotos("p2", "someone-elses");

        HostedDifferenceReport report = service.run();

        assertThat(report.albumsWithDifferences()).isEqualTo(1);
        AlbumFindings album = report.albums().getFirst();
        assertThat(album.itemInventoryId()).isEqualTo(100);
        assertThat(album.itemUuid()).isEqualTo("uuid-100");
        assertThat(album.hostedAlbumId()).isEqualTo("album-gone");
        assertThat(album.pendingWorkOnly()).isFalse();
        Finding finding = only(album, HostedDifferenceType.ALBUM_MISSING);
        assertThat(finding.detail()).contains("1 of its 2 photo(s) are still on the account");
        assertThat(finding.whatToDo()).isEqualTo(HostedDifferenceType.ALBUM_MISSING.whatToDo());
        assertThat(finding.accountLimitLikely()).isFalse();
    }

    @Test
    void photoCountInTheListingThatIsOffLeadsToAFullReadThatTellsDeletedFromTakenOut() {
        databaseAlbum(NOT_DUE, 100, "album-1");
        desired(100, "album-1", photo(11, true, "p1"), photo(12, false, "p2"), photo(13, false, "p3"));
        hostAlbums(hostAlbum("album-1", TITLE, DESCRIPTION, "p1", 1));
        albumPhotos("album-1", "p1");
        loosePhotos("p3");

        HostedDifferenceReport report = service.run();

        assertThat(report.albumsReadInFull()).isEqualTo(1);
        AlbumFindings album = report.albums().getFirst();
        assertThat(types(album)).containsExactly(
                HostedDifferenceType.PHOTOS_DELETED,
                HostedDifferenceType.PHOTOS_NOT_IN_ALBUM
        );
        assertThat(only(album, HostedDifferenceType.PHOTOS_DELETED).hostedPhotoIds()).containsExactly("p2");
        assertThat(only(album, HostedDifferenceType.PHOTOS_NOT_IN_ALBUM).hostedPhotoIds()).containsExactly("p3");
    }

    @Test
    void albumWhoseTurnItIsGetsAFullReadEvenWhenTheListingLooksRight() {
        // The listing still says two photos; the photo list shows one was deleted.
        databaseAlbum(DUE, 100, "album-1");
        desired(100, "album-1", photo(11, true, "p1"), photo(12, false, "p2"));
        hostAlbums(hostAlbum("album-1", TITLE, DESCRIPTION, "p1", 2));
        albumPhotos("album-1", "p1");
        loosePhotos();

        HostedDifferenceReport report = service.run();

        assertThat(report.albumsReadInFull()).isEqualTo(1);
        assertThat(types(report.albums().getFirst())).containsExactly(HostedDifferenceType.PHOTOS_DELETED);
    }

    @Test
    void everyAlbumIsReadInFullEveryNightWhenTheTurnIsOneDay() {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.getSync().getDifferenceReport().setFullCheckDays(1);
        LegoEnvironmentProperties environment = new LegoEnvironmentProperties();
        environment.setCode("sandbox");
        service = new ImageHostingDifferenceReportService(
                externalImageAlbumDao,
                desiredStateReader,
                new ImageHostingReconciliationPlanner(new ImageHostingPublishingPolicy(properties)),
                new HostedAccountListing(Optional.of(imageHostingService), new ImageHostingRetryTemplate(properties)),
                headroomGuard,
                properties,
                new ImageHostingSyncMetricsService(meterRegistry),
                environment,
                CLOCK
        );
        databaseAlbum(NOT_DUE, 100, "album-1");
        desired(100, "album-1", photo(11, true, "p1"));
        hostAlbums(hostAlbum("album-1", TITLE, DESCRIPTION, "p1", 1));
        albumPhotos("album-1", "p1");

        HostedDifferenceReport report = service.run();

        assertThat(report.albumsReadInFull()).isEqualTo(1);
        assertThat(report.albums()).isEmpty();
    }

    @Test
    void titleDescriptionCoverExtraPhotoAndOrderAreEachReported() {
        databaseAlbum(NOT_DUE, 100, "album-1");
        desired(100, "album-1", photo(11, true, "p1"), photo(12, false, "p2"));
        hostAlbums(hostAlbum("album-1", "Renamed by hand", "Something else", "p2", 3));
        albumPhotos("album-1", "p2", "p1", "stray");

        HostedDifferenceReport report = service.run();

        AlbumFindings album = report.albums().getFirst();
        assertThat(types(album)).containsExactlyInAnyOrder(
                HostedDifferenceType.TITLE,
                HostedDifferenceType.DESCRIPTION,
                HostedDifferenceType.EXTRA_PHOTOS,
                HostedDifferenceType.COVER
        );
        assertThat(only(album, HostedDifferenceType.TITLE).detail())
                .isEqualTo("Database: \"8865-1 - Test Car\". Image host: \"Renamed by hand\".");
        assertThat(only(album, HostedDifferenceType.EXTRA_PHOTOS).hostedPhotoIds()).containsExactly("stray");
        // Nothing is missing, so the photos outside any album are never read.
        verify(imageHostingService, never()).listPhotosNotInAlbum(any());
    }

    @Test
    void samePhotosInAnotherOrderIsReportedAsOrder() {
        databaseAlbum(DUE, 100, "album-1");
        desired(100, "album-1", photo(11, true, "p1"), photo(12, false, "p2"));
        hostAlbums(hostAlbum("album-1", TITLE, DESCRIPTION, "p1", 2));
        albumPhotos("album-1", "p2", "p1");

        HostedDifferenceReport report = service.run();

        assertThat(types(report.albums().getFirst())).containsExactly(HostedDifferenceType.PHOTO_ORDER);
    }

    @Test
    void deletedPhotosOnALimitedAccountNearItsLimitAreMarkedAsLikelyTheLimit() {
        when(headroomGuard.current()).thenReturn(new AccountHeadroom(true, 998, 1000, true, 100, 900, 0, null));
        databaseAlbum(NOT_DUE, 100, "album-1");
        desired(100, "album-1", photo(11, true, "p1"), photo(12, false, "p2"));
        hostAlbums(hostAlbum("album-1", TITLE, DESCRIPTION, "p1", 1));
        albumPhotos("album-1", "p1");
        loosePhotos();

        HostedDifferenceReport report = service.run();

        assertThat(report.account().itemCount()).isEqualTo(998);
        assertThat(only(report.albums().getFirst(), HostedDifferenceType.PHOTOS_DELETED).accountLimitLikely()).isTrue();
    }

    @Test
    void deletedPhotosOnAnAccountWithNoLimitAreNotMarked() {
        when(headroomGuard.current()).thenReturn(new AccountHeadroom(true, 24703, null, false, 100, null, null, null));
        databaseAlbum(NOT_DUE, 100, "album-1");
        desired(100, "album-1", photo(11, true, "p1"), photo(12, false, "p2"));
        hostAlbums(hostAlbum("album-1", TITLE, DESCRIPTION, "p1", 1));
        albumPhotos("album-1", "p1");
        loosePhotos();

        HostedDifferenceReport report = service.run();

        assertThat(only(report.albums().getFirst(), HostedDifferenceType.PHOTOS_DELETED).accountLimitLikely()).isFalse();
    }

    @Test
    void workTheSyncJobHasYetToDoIsListedApartFromDifferences() {
        databaseAlbum(NOT_DUE, 100, "album-1");
        desired(100, "album-1", photo(11, true, "p1"), photo(12, false, null));
        hostAlbums(hostAlbum("album-1", TITLE, DESCRIPTION, "p1", 1));
        albumPhotos("album-1", "p1");

        HostedDifferenceReport report = service.run();

        assertThat(report.albumsWithDifferences()).isZero();
        assertThat(report.albumsWithPendingWorkOnly()).isEqualTo(1);
        AlbumFindings album = report.albums().getFirst();
        assertThat(album.pendingWorkOnly()).isTrue();
        assertThat(only(album, HostedDifferenceType.PHOTOS_NOT_PUBLISHED).detail())
                .isEqualTo("1 photo(s) not published: photo-12.jpg");
    }

    @Test
    void albumsNotPublishedYetAndAlbumsOfAnotherHostAreNotCompared() {
        when(externalImageAlbumDao.findAll()).thenReturn(new LinkedHashSet<>(List.of(
                ExternalImageAlbum.builder().externalImageAlbumId(1L).externalServiceId(FLICKR).itemInventoryId(100).build(),
                ExternalImageAlbum.builder().externalImageAlbumId(2L).externalServiceId(99).itemInventoryId(101)
                        .externalAlbumId("elsewhere").build()
        )));
        hostAlbums(hostAlbum("legacy-album", "Made by the legacy system", "", "x", 4));

        HostedDifferenceReport report = service.run();

        assertThat(report.albumsInDatabase()).isEqualTo(1);
        assertThat(report.albumsNotPublished()).isEqualTo(1);
        assertThat(report.albumsChecked()).isZero();
        assertThat(report.albumsOnHost()).isEqualTo(1);
        assertThat(report.albumsOnHostUnknown()).isEqualTo(1);
        assertThat(report.albums()).isEmpty();
        verify(desiredStateReader, never()).read(any());
    }

    @Test
    void oneAlbumThatCannotBeComparedDoesNotHideTheOthers() {
        when(externalImageAlbumDao.findAll()).thenReturn(new LinkedHashSet<>(List.of(
                album(NOT_DUE, 100, "album-1"),
                album(NOT_DUE + 7, 101, "album-2")
        )));
        when(desiredStateReader.read(argThat(request -> request != null && request.getItemInventoryId() == 100)))
                .thenThrow(new IllegalArgumentException("No item inventory found for id [100]"));
        desired(101, "album-2", photo(21, true, "q1"));
        hostAlbums(
                hostAlbum("album-1", TITLE, DESCRIPTION, "p1", 1),
                hostAlbum("album-2", "Renamed", DESCRIPTION, "q1", 1)
        );
        albumPhotos("album-2", "q1");

        HostedDifferenceReport report = service.run();

        assertThat(report.complete()).isTrue();
        assertThat(report.albums()).hasSize(2);
        assertThat(only(report.albums().get(0), HostedDifferenceType.CHECK_FAILED).detail())
                .isEqualTo("No item inventory found for id [100]");
        assertThat(types(report.albums().get(1))).containsExactly(HostedDifferenceType.TITLE);
    }

    @Test
    void whenTheAlbumListingFailsNothingIsReportedAsMissing() {
        when(imageHostingService.listAlbums(any()))
                .thenReturn(error(PhotoServiceErrorType.AUTHENTICATION_FAILED, "Invalid auth token"));

        HostedDifferenceReport report = service.run();

        assertThat(report.complete()).isFalse();
        assertThat(report.message()).contains("listAlbums").contains("Invalid auth token");
        assertThat(report.albums()).isEmpty();
        verify(externalImageAlbumDao, never()).findAll();
        assertThat(meterRegistry.get("image_hosting_difference_report").tag("outcome", "failed").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    void theLastCompleteRunIsPublishedAsGauges() {
        databaseAlbum(NOT_DUE, 100, "album-gone");
        desired(100, "album-gone", photo(11, true, "p1"));
        hostAlbums();
        loosePhotos();

        service.run();

        assertThat(meterRegistry.get("image_hosting_difference_report").tag("outcome", "completed").counter().count())
                .isEqualTo(1.0d);
        assertThat(meterRegistry.get("image_hosting_difference_report_albums_checked").gauge().value()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("image_hosting_difference_report_albums_different").gauge().value()).isEqualTo(1.0d);
    }

    @Test
    void noReportBeforeTheFirstRun() {
        assertThat(service.last()).isEmpty();
    }

    private void databaseAlbum(long rowId, int itemInventoryId, String hostedAlbumId) {
        when(externalImageAlbumDao.findAll()).thenReturn(Set.of(album(rowId, itemInventoryId, hostedAlbumId)));
    }

    private static ExternalImageAlbum album(long rowId, int itemInventoryId, String hostedAlbumId) {
        return ExternalImageAlbum.builder()
                .externalImageAlbumId(rowId)
                .externalServiceId(FLICKR)
                .itemInventoryId(itemInventoryId)
                .externalAlbumId(hostedAlbumId)
                .albumUrl("https://www.flickr.com/photos/user/albums/" + hostedAlbumId)
                .build();
    }

    private void desired(int itemInventoryId, String hostedAlbumId, DesiredImageHostingPhoto... photos) {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(itemInventoryId);
        inventory.setUuid("uuid-" + itemInventoryId);
        ImageHostingDesiredStateSnapshot snapshot = ImageHostingDesiredStateSnapshot.builder()
                .provider("flickr")
                .externalServiceId(FLICKR)
                .inventory(inventory)
                .album(DesiredImageHostingAlbum.builder()
                        .desiredTitle(TITLE)
                        .desiredDescription(DESCRIPTION)
                        .externalAlbum(ExternalImageAlbum.builder().externalImageAlbumId(1L).externalAlbumId(hostedAlbumId).build())
                        .build())
                .photos(List.of(photos))
                .build();
        when(desiredStateReader.read(argThat(request -> request != null && request.getItemInventoryId() == itemInventoryId)))
                .thenReturn(snapshot);
    }

    private static DesiredImageHostingPhoto photo(int itemInventoryPhotoId, boolean primary, String hostedPhotoId) {
        return DesiredImageHostingPhoto.builder()
                .inventoryPhoto(ItemInventoryPhoto.builder()
                        .itemInventoryPhotoId(itemInventoryPhotoId)
                        .fileName("photo-" + itemInventoryPhotoId + ".jpg")
                        .primary(primary)
                        .build())
                .externalImage(hostedPhotoId == null
                        ? null
                        : ExternalImage.builder().externalImageId((long) itemInventoryPhotoId).externalServiceImageId(hostedPhotoId).build())
                .build();
    }

    private void hostAlbums(HostedAlbum... albums) {
        when(imageHostingService.listAlbums(any()))
                .thenReturn(ok(HostedAlbumPage.builder().albums(List.of(albums)).page(1).pages(1).build()));
    }

    private static HostedAlbum hostAlbum(String id, String title, String description, String coverPhotoId, int photoCount) {
        return HostedAlbum.builder()
                .id(id)
                .title(title)
                .description(description)
                .primaryPhotoId(coverPhotoId)
                .photoCount(photoCount)
                .build();
    }

    private void albumPhotos(String albumId, String... photoIds) {
        when(imageHostingService.listAlbumPhotos(argThat(request -> request != null && albumId.equals(request.get().getAlbumId()))))
                .thenReturn(ok(photoPage(photoIds)));
    }

    private void loosePhotos(String... photoIds) {
        when(imageHostingService.listPhotosNotInAlbum(any())).thenReturn(ok(photoPage(photoIds)));
    }

    private static HostedPhotoPage photoPage(String... photoIds) {
        return HostedPhotoPage.builder()
                .photos(Arrays.stream(photoIds).map(id -> HostedPhoto.builder().id(id).build()).toList())
                .page(1)
                .pages(1)
                .build();
    }

    private static List<HostedDifferenceType> types(AlbumFindings album) {
        return album.findings().stream().map(Finding::type).toList();
    }

    private static Finding only(AlbumFindings album, HostedDifferenceType type) {
        List<Finding> matches = album.findings().stream().filter(finding -> finding.type() == type).toList();
        assertThat(matches).as("findings of type %s in %s", type, types(album)).hasSize(1);
        return matches.getFirst();
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
