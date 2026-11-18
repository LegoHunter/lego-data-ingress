package io.legohunter.egress.imagehosting.plan;

import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.egress.imagehosting.publishing.ImageHostingPublishingPolicy;
import io.legohunter.egress.imagehosting.remote.ImageHostingRemoteSnapshot;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingAlbum;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingPhoto;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateSnapshot;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedPhoto;
import io.legohunter.imaging.service.sync.model.SyncAction;
import io.legohunter.imaging.service.sync.model.SyncActionSafety;
import io.legohunter.imaging.service.sync.model.SyncActionType;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.imaging.service.sync.model.SyncPlanMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class ImageHostingReconciliationPlannerTest {
    private final ImageHostingReconciliationPlanner planner = new ImageHostingReconciliationPlanner(
            new ImageHostingPublishingPolicy(new ImageHostingSyncProperties())
    );

    @Test
    void planCreatesAlbumAndUploadsPhotosWhenDbStateHasNoExternalIds() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album(null),
                List.of(desiredPhoto(11, true, null, null, null))
        );

        SyncPlan plan = planner.plan(desiredState, null);

        assertThat(plan.getMode()).isEqualTo(SyncPlanMode.DRY_RUN);
        assertThat(plan.getActions())
                .extracting(SyncAction::getType)
                .containsExactly(SyncActionType.UPLOAD_PHOTO, SyncActionType.CREATE_ALBUM);
        assertThat(plan.getActions())
                .extracting(SyncAction::getSafety)
                .containsExactly(SyncActionSafety.SAFE_AUTOMATIC, SyncActionSafety.SAFE_AUTOMATIC);
        assertThat(plan.getActions().getFirst().getAttributes())
                .containsEntry("itemInventoryPhotoId", "11")
                .containsEntry("primary", "true");
    }

    @Test
    void planUpdatesAlbumAndPhotoMetadataWhenDbDesiredMetadataChanged() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(desiredPhoto(11, true, "photo-11", "metadata-new", "metadata-old"))
        );
        ImageHostingRemoteSnapshot remoteSnapshot = remoteSnapshot(
                hostedAlbum("album-100", "Old title", "Old description", "photo-11"),
                List.of(hostedPhoto("photo-11"))
        );

        SyncPlan plan = planner.plan(desiredState, remoteSnapshot);

        assertThat(plan.getActions())
                .extracting(SyncAction::getType)
                .containsExactly(SyncActionType.UPDATE_PHOTO_METADATA, SyncActionType.UPDATE_ALBUM_METADATA);
        assertThat(plan.getActions().getFirst().getAttributes())
                .containsEntry("metadataHash", "metadata-new")
                .containsEntry("metadataHashAtSync", "metadata-old")
                .containsEntry("desiredTitle", "Caption 11")
                .containsEntry("desiredDescription", "Caption 11");
    }

    @Test
    void planRequiresReviewWhenDbAlbumIdIsMissingRemotely() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(desiredPhoto(11, true, "photo-11", "metadata-11", "metadata-11"))
        );

        SyncPlan plan = planner.plan(desiredState, ImageHostingRemoteSnapshot.builder()
                .provider("flickr")
                .externalServiceId(10)
                .requestedAlbumId("album-100")
                .build());

        assertThat(plan.getActions())
                .extracting(SyncAction::getType)
                .containsExactly(SyncActionType.REPAIR_ALBUM_ID);
        assertThat(plan.getActions().getFirst().getSafety()).isEqualTo(SyncActionSafety.REQUIRES_REVIEW);
    }

    @Test
    void planUpdatesAlbumMembershipWhenDbPhotoIdIsMissingFromRemoteAlbum() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(desiredPhoto(11, true, "photo-11", "metadata-11", "metadata-11"))
        );
        ImageHostingRemoteSnapshot remoteSnapshot = remoteSnapshot(
                hostedAlbum("album-100", "Desired album", "Inventory item [inventory-uuid]", null),
                List.of()
        );

        SyncPlan plan = planner.plan(desiredState, remoteSnapshot);

        assertThat(plan.getActions())
                .extracting(SyncAction::getType)
                .containsExactly(SyncActionType.UPDATE_ALBUM_MEMBERSHIP);
        assertThat(plan.getActions().getFirst().getSafety()).isEqualTo(SyncActionSafety.SAFE_AUTOMATIC);
        assertThat(plan.getActions().getFirst().getAttributes())
                .containsEntry("desiredOnlyPhotoIds", "photo-11")
                .containsEntry("remoteOnlyPhotoIds", "");
    }

    @Test
    void planRequiresReviewForMembershipUpdateThatWouldRemoveRemoteOnlyPhotos() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(desiredPhoto(11, true, "photo-11", "metadata-11", "metadata-11"))
        );
        ImageHostingRemoteSnapshot remoteSnapshot = remoteSnapshot(
                hostedAlbum("album-100", "Desired album", "Inventory item [inventory-uuid]", "photo-remote-only"),
                List.of(hostedPhoto("photo-remote-only"), hostedPhoto("photo-11"))
        );

        SyncPlan plan = planner.plan(desiredState, remoteSnapshot);

        assertThat(plan.getActions())
                .extracting(SyncAction::getType)
                .containsExactly(SyncActionType.UPDATE_ALBUM_MEMBERSHIP);
        assertThat(plan.getActions().getFirst().getSafety()).isEqualTo(SyncActionSafety.REQUIRES_REVIEW);
        assertThat(plan.getActions().getFirst().getAttributes())
                .containsEntry("desiredPhotoIds", "photo-11")
                .containsEntry("remotePhotoIds", "photo-remote-only,photo-11")
                .containsEntry("remoteOnlyPhotoIds", "photo-remote-only");
    }

    @Test
    void planFixesPrimaryPhotoWhenMembershipAlreadyMatches() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(
                        desiredPhoto(11, true, "photo-11", "metadata-11", "metadata-11"),
                        desiredPhoto(12, false, "photo-12", "metadata-12", "metadata-12")
                )
        );
        ImageHostingRemoteSnapshot remoteSnapshot = remoteSnapshot(
                hostedAlbum("album-100", "Desired album", "Inventory item [inventory-uuid]", "photo-12"),
                List.of(hostedPhoto("photo-11"), hostedPhoto("photo-12"))
        );

        SyncPlan plan = planner.plan(desiredState, remoteSnapshot);

        assertThat(plan.getActions())
                .extracting(SyncAction::getType)
                .containsExactly(SyncActionType.FIX_PRIMARY_PHOTO);
        assertThat(plan.getActions().getFirst().getSafety()).isEqualTo(SyncActionSafety.SAFE_AUTOMATIC);
    }

    @Test
    void planReplacesAPhotoWhoseStoredFileChangedSinceUpload() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(
                        uploadedPhoto(11, true, "photo-11", "md5-new", "md5-old", "metadata-11"),
                        uploadedPhoto(12, false, "photo-12", "md5-12", "md5-12", "metadata-12")
                )
        );
        ImageHostingRemoteSnapshot remoteSnapshot = remoteSnapshot(
                hostedAlbum("album-100", "Desired album", "Inventory item [inventory-uuid]", "photo-11"),
                List.of(hostedPhoto("photo-11"), hostedPhoto("photo-12"))
        );

        SyncPlan plan = planner.plan(desiredState, remoteSnapshot);

        // Only the changed photo, and nothing for the album: its order and cover are already right.
        assertThat(plan.getActions())
                .extracting(SyncAction::getType)
                .containsExactly(SyncActionType.REPLACE_PHOTO);
        SyncAction replace = plan.getActions().getFirst();
        assertThat(replace.getSafety()).isEqualTo(SyncActionSafety.SAFE_AUTOMATIC);
        assertThat(replace.getPhotoId()).isEqualTo("photo-11");
        assertThat(replace.getAlbumId()).isEqualTo("album-100");
        assertThat(replace.getAttributes())
                .containsEntry("itemInventoryPhotoId", "11")
                .containsEntry("md5", "md5-new")
                .containsEntry("md5AtUpload", "md5-old")
                // The album as the host has it, so a new photo id can take the old one's exact place.
                .containsEntry("remotePhotoIds", "photo-11,photo-12")
                .containsEntry("remotePrimaryPhotoId", "photo-11");
    }

    @Test
    void planDoesNotReplaceAPhotoWhoseFileIsUnchanged() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(
                        uploadedPhoto(11, true, "photo-11", "md5-11", "md5-11", "metadata-11"),
                        // No hash recorded at upload: what the host holds is unknown, so nothing is replaced.
                        uploadedPhoto(12, false, "photo-12", "md5-12", null, "metadata-12")
                )
        );
        ImageHostingRemoteSnapshot remoteSnapshot = remoteSnapshot(
                hostedAlbum("album-100", "Desired album", "Inventory item [inventory-uuid]", "photo-11"),
                List.of(hostedPhoto("photo-11"), hostedPhoto("photo-12"))
        );

        assertThat(planner.plan(desiredState, remoteSnapshot).getActions()).isEmpty();
    }

    @Test
    void planKeepsACaptionOnlyChangeOnTheMetadataPathAndPlansBothWhenBothChanged() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(
                        // Caption changed, file unchanged.
                        uploadedPhoto(11, true, "photo-11", "md5-11", "md5-11", "metadata-old"),
                        // File and caption both changed.
                        uploadedPhoto(12, false, "photo-12", "md5-new", "md5-old", "metadata-old")
                )
        );
        ImageHostingRemoteSnapshot remoteSnapshot = remoteSnapshot(
                hostedAlbum("album-100", "Desired album", "Inventory item [inventory-uuid]", "photo-11"),
                List.of(hostedPhoto("photo-11"), hostedPhoto("photo-12"))
        );

        SyncPlan plan = planner.plan(desiredState, remoteSnapshot);

        assertThat(plan.getActions())
                .extracting(SyncAction::getType, SyncAction::getPhotoId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(SyncActionType.UPDATE_PHOTO_METADATA, "photo-11"),
                        org.assertj.core.groups.Tuple.tuple(SyncActionType.REPLACE_PHOTO, "photo-12"),
                        org.assertj.core.groups.Tuple.tuple(SyncActionType.UPDATE_PHOTO_METADATA, "photo-12")
                );
    }

    @Test
    void planBlocksWhenRemoteSnapshotFailed() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(desiredPhoto(11, true, "photo-11", "metadata-11", "metadata-11"))
        );

        SyncPlan plan = planner.plan(desiredState, ImageHostingRemoteSnapshot.builder()
                .provider("flickr")
                .externalServiceId(10)
                .requestedAlbumId("album-100")
                .failureMessage("Flickr unavailable")
                .build());

        assertThat(plan.getActions())
                .extracting(SyncAction::getType, SyncAction::getSafety)
                .containsExactly(tuple(
                        SyncActionType.REPAIR_ALBUM_ID,
                        SyncActionSafety.BLOCKED
                ));
    }

    @Test
    void planRejectsMissingDesiredState() {
        assertThatThrownBy(() -> planner.plan(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desiredState is required");
    }

    private static ImageHostingDesiredStateSnapshot desiredState(
            DesiredImageHostingAlbum album,
            List<DesiredImageHostingPhoto> photos
    ) {
        return ImageHostingDesiredStateSnapshot.builder()
                .provider("flickr")
                .externalServiceId(10)
                .inventory(inventory())
                .album(album)
                .photos(photos)
                .build();
    }

    private static DesiredImageHostingAlbum album(String externalAlbumId) {
        ExternalImageAlbum externalAlbum = externalAlbumId == null
                ? null
                : ExternalImageAlbum.builder()
                        .externalImageAlbumId(301L)
                        .externalAlbumId(externalAlbumId)
                        .build();
        return DesiredImageHostingAlbum.builder()
                .desiredTitle("Desired album")
                .desiredDescription("Inventory item [inventory-uuid]")
                .externalAlbum(externalAlbum)
                .build();
    }

    private static DesiredImageHostingPhoto desiredPhoto(
            Integer itemInventoryPhotoId,
            boolean primary,
            String externalServiceImageId,
            String metadataHash,
            String metadataHashAtSync
    ) {
        ExternalImage externalImage = externalServiceImageId == null
                ? null
                : ExternalImage.builder()
                        .externalImageId(200L + itemInventoryPhotoId)
                        .externalServiceImageId(externalServiceImageId)
                        .metadataHashAtSync(metadataHashAtSync)
                        .build();
        ExternalImageAlbumImage membership = externalImage == null
                ? null
                : ExternalImageAlbumImage.builder()
                        .externalImageAlbumId(301L)
                        .externalImageId(externalImage.getExternalImageId())
                        .sortOrder(itemInventoryPhotoId)
                        .primary(primary)
                        .build();
        return DesiredImageHostingPhoto.builder()
                .inventoryPhoto(ItemInventoryPhoto.builder()
                        .itemInventoryPhotoId(itemInventoryPhotoId)
                        .itemInventoryId(100)
                        .fileName("photo-" + itemInventoryPhotoId + ".jpg")
                        .metadataHash(metadataHash)
                        .primary(primary)
                        .caption("Caption " + itemInventoryPhotoId)
                        .build())
                .externalImage(externalImage)
                .albumMembership(membership)
                .build();
    }

    /** A published photo: its stored file's hash, the hash recorded at upload, and metadata last sent. */
    private static DesiredImageHostingPhoto uploadedPhoto(
            Integer itemInventoryPhotoId,
            boolean primary,
            String externalServiceImageId,
            String md5,
            String md5AtUpload,
            String metadataHashAtSync
    ) {
        DesiredImageHostingPhoto photo = desiredPhoto(
                itemInventoryPhotoId,
                primary,
                externalServiceImageId,
                "metadata-" + itemInventoryPhotoId,
                metadataHashAtSync
        );
        photo.getInventoryPhoto().setMd5(md5);
        photo.getExternalImage().setMd5AtUpload(md5AtUpload);
        return photo;
    }

    private static ImageHostingRemoteSnapshot remoteSnapshot(HostedAlbum album, List<HostedPhoto> photos) {
        return ImageHostingRemoteSnapshot.builder()
                .provider("flickr")
                .externalServiceId(10)
                .requestedAlbumId(album.getId())
                .album(album)
                .photos(photos)
                .build();
    }

    private static HostedAlbum hostedAlbum(String id, String title, String description, String primaryPhotoId) {
        return HostedAlbum.builder()
                .id(id)
                .title(title)
                .description(description)
                .primaryPhotoId(primaryPhotoId)
                .build();
    }

    private static HostedPhoto hostedPhoto(String id) {
        return HostedPhoto.builder()
                .id(id)
                .title("Hosted " + id)
                .description("Hosted description " + id)
                .build();
    }

    private static ItemInventory inventory() {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(100);
        inventory.setUuid("inventory-uuid");
        return inventory;
    }
}
