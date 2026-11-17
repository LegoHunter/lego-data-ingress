package io.legohunter.egress.imagehosting.repair;

import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.egress.imagehosting.ImageHostingRetryTemplate;
import io.legohunter.egress.imagehosting.ImageHostingSyncMetricsService;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.egress.imagehosting.publishing.ImageHostingPublishingPolicy;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingAlbum;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingPhoto;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateReader;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateRequest;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateSnapshot;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumPage;
import io.legohunter.imaging.model.HostedPhoto;
import io.legohunter.imaging.model.HostedPhotoPage;
import io.legohunter.imaging.model.PhotoServiceErrorType;
import io.legohunter.imaging.model.PhotoServiceRequest;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import io.legohunter.imaging.service.sync.model.SyncActionSafety;
import io.legohunter.imaging.service.sync.model.SyncActionType;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static io.legohunter.egress.imagehosting.repair.ImageHostingDbRepairAttributes.REMOTE_ADOPTION_AMBIGUOUS;
import static io.legohunter.egress.imagehosting.repair.ImageHostingDbRepairAttributes.REMOTE_ADOPTION_FAILED;
import static io.legohunter.egress.imagehosting.repair.ImageHostingDbRepairAttributes.REMOTE_ADOPTION_NOT_FOUND;
import static io.legohunter.egress.imagehosting.repair.ImageHostingDbRepairAttributes.REMOTE_ADOPTION_STATUS;

class DefaultImageHostingDbRepairPlanServiceTest {
    private static final int FLICKR_SERVICE_ID = 10;

    private ImageHostingDesiredStateReader desiredStateReader;
    private ImageHostingService imageHostingService;
    private SimpleMeterRegistry meterRegistry;
    private DefaultImageHostingDbRepairPlanService service;

    @BeforeEach
    void setUp() {
        desiredStateReader = mock(ImageHostingDesiredStateReader.class);
        imageHostingService = mock(ImageHostingService.class);
        meterRegistry = new SimpleMeterRegistry();
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.getSync().getRetry().setInitialBackoffMs(0L);
        service = new DefaultImageHostingDbRepairPlanService(
                desiredStateReader,
                Optional.of(imageHostingService),
                new ImageHostingRetryTemplate(properties),
                new ImageHostingPublishingPolicy(properties),
                new ImageHostingSyncMetricsService(meterRegistry)
        );
    }

    @Test
    void planRepairsMissingAlbumPhotoIdsAndMembershipFromUniqueRemoteMatches() {
        when(desiredStateReader.read(any())).thenReturn(desiredStateWithoutExternalIds());
        when(imageHostingService.listAlbums(any())).thenReturn(response(HostedAlbumPage.builder()
                .album(remoteAlbum("album-099", "4558-1 - Metroliner"))
                .album(remoteAlbum("album-100", "4558-1 - Metroliner", "Generated.\n\nItem reference: inventory-uuid"))
                .page(1)
                .pages(1)
                .build()));
        when(imageHostingService.listAlbumPhotos(any())).thenReturn(response(HostedPhotoPage.builder()
                .photo(remotePhoto("photo-11", "Front caption", true))
                .photo(remotePhoto("photo-12", "Detail caption", false))
                .page(1)
                .pages(1)
                .build()));

        SyncPlan plan = service.plan(ImageHostingDbRepairPlanRequest.builder()
                .itemInventoryId(100)
                .build());

        assertThat(plan.getPlanId()).startsWith("image-hosting-repair-flickr-100-");
        assertThat(plan.getActions())
                .extracting(action -> action.getType())
                .containsExactly(
                        SyncActionType.REPAIR_ALBUM_ID,
                        SyncActionType.REPAIR_PHOTO_ID,
                        SyncActionType.REPAIR_PHOTO_ID,
                        SyncActionType.UPDATE_ALBUM_MEMBERSHIP
                );
        assertThat(plan.getActions())
                .extracting(action -> action.getSafety())
                .containsExactly(
                        SyncActionSafety.SAFE_AUTOMATIC,
                        SyncActionSafety.SAFE_AUTOMATIC,
                        SyncActionSafety.SAFE_AUTOMATIC,
                        SyncActionSafety.SAFE_AUTOMATIC
                );
        assertThat(plan.getActions().getFirst().getAttributes())
                .containsEntry("remoteAlbumId", "album-100")
                .containsEntry("remoteAlbumUrl", "https://flickr.example/albums/album-100");
        assertThat(plan.getActions().get(1).getAttributes())
                .containsEntry("itemInventoryPhotoId", "11")
                .containsEntry("remotePhotoId", "photo-11");
        assertThat(plan.getActions().getLast().getAttributes())
                .containsEntry("remotePhotoIds", "photo-11,photo-12")
                .containsEntry("remotePrimaryPhotoId", "photo-11");
    }

    @Test
    void planAdoptsALegacyAlbumWhoseDescriptionIsTheItemUuid() {
        when(desiredStateReader.read(any())).thenReturn(desiredStateWithoutExternalIds());
        when(imageHostingService.listAlbums(any())).thenReturn(response(HostedAlbumPage.builder()
                .album(remoteAlbum("album-100", "A title the legacy system chose", "inventory-uuid"))
                .page(1)
                .pages(1)
                .build()));
        when(imageHostingService.listAlbumPhotos(any())).thenReturn(response(HostedPhotoPage.builder()
                .page(1)
                .pages(1)
                .build()));

        SyncPlan plan = service.plan(ImageHostingDbRepairPlanRequest.builder()
                .itemInventoryId(100)
                .build());

        assertThat(plan.getActions().getFirst())
                .extracting(action -> action.getType(), action -> action.getSafety())
                .containsExactly(SyncActionType.REPAIR_ALBUM_ID, SyncActionSafety.SAFE_AUTOMATIC);
        assertThat(plan.getActions().getFirst().getAttributes()).containsEntry("remoteAlbumId", "album-100");
    }

    @Test
    void planDoesNotAdoptAnAlbumThatOnlySharesTheTitle() {
        // Another copy of the same set already has an album: same title, another item's UUID.
        when(desiredStateReader.read(any())).thenReturn(desiredStateWithoutExternalIds());
        when(imageHostingService.listAlbums(any())).thenReturn(response(HostedAlbumPage.builder()
                .album(remoteAlbum("album-100", "4558-1 - Metroliner", "Generated.\n\nItem reference: other-copy-uuid"))
                .album(remoteAlbum("album-101", "4558-1 - Metroliner", "inventory-uuid-of-a-third-copy"))
                .page(1)
                .pages(1)
                .build()));

        SyncPlan plan = service.plan(ImageHostingDbRepairPlanRequest.builder()
                .itemInventoryId(100)
                .build());

        assertThat(plan.getActions()).hasSize(1);
        assertThat(plan.getActions().getFirst().getAttributes())
                .containsEntry(REMOTE_ADOPTION_STATUS, REMOTE_ADOPTION_NOT_FOUND);
    }

    @Test
    void planBlocksWhenSeveralRemoteAlbumsCarryTheItemUuid() {
        when(desiredStateReader.read(any())).thenReturn(desiredStateWithoutExternalIds());
        when(imageHostingService.listAlbums(any())).thenReturn(response(HostedAlbumPage.builder()
                .album(remoteAlbum("album-100", "4558-1 - Metroliner", "inventory-uuid"))
                .album(remoteAlbum("album-101", "Renamed album", "Generated.\n\nItem reference: inventory-uuid"))
                .page(1)
                .pages(1)
                .build()));

        SyncPlan plan = service.plan(ImageHostingDbRepairPlanRequest.builder()
                .itemInventoryId(100)
                .build());

        assertThat(plan.getActions()).hasSize(1);
        assertThat(plan.getActions().getFirst())
                .extracting(
                        action -> action.getType(),
                        action -> action.getSafety(),
                        action -> action.getDescription()
                )
                .containsExactly(
                        SyncActionType.REPAIR_ALBUM_ID,
                        SyncActionSafety.BLOCKED,
                        "Multiple remote Flickr albums carry this item's UUID; repair is ambiguous"
                );
        assertThat(plan.getActions().getFirst().getAttributes())
                .containsEntry("matchingRemoteAlbumIds", "album-100,album-101")
                .containsEntry(REMOTE_ADOPTION_STATUS, REMOTE_ADOPTION_AMBIGUOUS);
        assertThat(meterRegistry.counter(
                "image_hosting_album_adoption",
                "provider", "flickr",
                "result", "ambiguous"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void planBuildsBlockedActionWhenProviderReadFails() {
        when(desiredStateReader.read(any())).thenReturn(desiredStateWithoutExternalIds());
        when(imageHostingService.listAlbums(any())).thenReturn(error("Flickr unavailable"));

        SyncPlan plan = service.plan(ImageHostingDbRepairPlanRequest.builder()
                .itemInventoryId(100)
                .build());

        assertThat(plan.getActions()).hasSize(1);
        assertThat(plan.getActions().getFirst())
                .extracting(action -> action.getType(), action -> action.getSafety())
                .containsExactly(SyncActionType.REPAIR_ALBUM_ID, SyncActionSafety.BLOCKED);
        assertThat(plan.getActions().getFirst().getAttributes().get("failureMessages"))
                .startsWith("Flickr unavailable");
        assertThat(plan.getActions().getFirst().getAttributes())
                .containsEntry(REMOTE_ADOPTION_STATUS, REMOTE_ADOPTION_FAILED);
        assertThat(meterRegistry.counter(
                "image_hosting_album_adoption",
                "provider", "flickr",
                "result", "failed"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void planRecordsNoMatchMetricWhenRemoteAlbumAdoptionFindsNoMatch() {
        when(desiredStateReader.read(any())).thenReturn(desiredStateWithoutExternalIds());
        when(imageHostingService.listAlbums(any())).thenReturn(response(HostedAlbumPage.builder()
                .album(remoteAlbum("album-200", "Other album"))
                .page(1)
                .pages(1)
                .build()));

        SyncPlan plan = service.plan(ImageHostingDbRepairPlanRequest.builder()
                .itemInventoryId(100)
                .build());

        assertThat(plan.getActions()).hasSize(1);
        assertThat(plan.getActions().getFirst().getAttributes())
                .containsEntry(REMOTE_ADOPTION_STATUS, REMOTE_ADOPTION_NOT_FOUND);
        assertThat(meterRegistry.counter(
                "image_hosting_album_adoption",
                "provider", "flickr",
                "result", "no_match"
        ).count()).isEqualTo(1.0);
    }

    private static ImageHostingDesiredStateSnapshot desiredStateWithoutExternalIds() {
        return ImageHostingDesiredStateSnapshot.builder()
                .provider("flickr")
                .externalServiceId(FLICKR_SERVICE_ID)
                .inventory(inventory())
                .album(DesiredImageHostingAlbum.builder()
                        .desiredTitle("4558-1 - Metroliner")
                        .desiredDescription("Generated description")
                        .build())
                .photo(DesiredImageHostingPhoto.builder()
                        .inventoryPhoto(photo(11, "Front caption", true))
                        .build())
                .photo(DesiredImageHostingPhoto.builder()
                        .inventoryPhoto(photo(12, "Detail caption", false))
                        .build())
                .build();
    }

    private static ItemInventoryPhoto photo(Integer itemInventoryPhotoId, String caption, boolean primary) {
        return ItemInventoryPhoto.builder()
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .itemInventoryId(100)
                .fileName("photo-%s.jpg".formatted(itemInventoryPhotoId))
                .caption(caption)
                .primary(primary)
                .build();
    }

    private static ItemInventory inventory() {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(100);
        inventory.setUuid("inventory-uuid");
        return inventory;
    }

    private static HostedAlbum remoteAlbum(String id, String title) {
        return remoteAlbum(id, title, "Remote description");
    }

    private static HostedAlbum remoteAlbum(String id, String title, String description) {
        return HostedAlbum.builder()
                .id(id)
                .url("https://flickr.example/albums/" + id)
                .title(title)
                .description(description)
                .primaryPhotoId("photo-11")
                .build();
    }

    private static HostedPhoto remotePhoto(String id, String title, boolean primary) {
        return HostedPhoto.builder()
                .id(id)
                .title(title)
                .description(title)
                .url("https://flickr.example/photos/" + id)
                .primary(primary)
                .build();
    }

    private static <T> PhotoServiceResponse<T> response(T value) {
        return new TestPhotoServiceResponse<>(value, false, null, null, PhotoServiceErrorType.UNKNOWN);
    }

    private static <T> PhotoServiceResponse<T> error(String message) {
        return new TestPhotoServiceResponse<>(null, true, 503, message, PhotoServiceErrorType.SERVICE_UNAVAILABLE);
    }

    private record TestPhotoServiceResponse<T>(
            T value,
            boolean error,
            Integer responseCode,
            String responseMessage,
            PhotoServiceErrorType errorType
    ) implements PhotoServiceResponse<T> {
        @Override
        public boolean isError() {
            return error;
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
