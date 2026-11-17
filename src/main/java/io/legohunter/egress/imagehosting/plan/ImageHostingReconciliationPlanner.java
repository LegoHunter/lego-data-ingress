package io.legohunter.egress.imagehosting.plan;

import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ItemInventoryPhoto;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class ImageHostingReconciliationPlanner {
    private final ImageHostingPublishingPolicy publishingPolicy;

    public SyncPlan plan(ImageHostingDesiredStateSnapshot desiredState, ImageHostingRemoteSnapshot remoteSnapshot) {
        if (desiredState == null) {
            throw new IllegalArgumentException("desiredState is required");
        }

        PlanActionBuilder actions = new PlanActionBuilder(desiredState);
        ImageHostingRemoteSnapshot remote = Optional.ofNullable(remoteSnapshot)
                .orElseGet(() -> emptyRemoteSnapshot(desiredState));

        if (!remote.getFailureMessages().isEmpty()) {
            actions.add(
                    SyncActionType.REPAIR_ALBUM_ID,
                    SyncActionSafety.BLOCKED,
                    "Remote snapshot failed; reconciliation cannot safely compare DB state to Flickr state",
                    Map.of("failureMessages", String.join("; ", remote.getFailureMessages()))
            );
            return buildPlan(desiredState, actions);
        }

        DesiredImageHostingAlbum desiredAlbum = desiredState.getAlbum();
        if (desiredAlbum == null) {
            actions.add(
                    SyncActionType.CREATE_ALBUM,
                    SyncActionSafety.BLOCKED,
                    "Desired album state is missing",
                    Map.of()
            );
            return buildPlan(desiredState, actions);
        }

        planPhotoActions(desiredState, remote, actions);
        planAlbumActions(desiredAlbum, remote, actions);
        planMembershipActions(desiredState, remote, actions);

        return buildPlan(desiredState, actions);
    }

    private void planAlbumActions(
            DesiredImageHostingAlbum desiredAlbum,
            ImageHostingRemoteSnapshot remote,
            PlanActionBuilder actions
    ) {
        String desiredAlbumId = desiredAlbum.getExternalAlbumId();
        if (!hasText(desiredAlbumId)) {
            actions.add(
                    SyncActionType.CREATE_ALBUM,
                    SyncActionSafety.SAFE_AUTOMATIC,
                    "Create missing Flickr album",
                    Map.of(
                            "desiredTitle", value(desiredAlbum.getDesiredTitle()),
                            "desiredDescription", value(desiredAlbum.getDesiredDescription())
                    )
            );
            return;
        }

        if (!remote.isAlbumFound()) {
            actions.add(
                    SyncActionType.REPAIR_ALBUM_ID,
                    SyncActionSafety.REQUIRES_REVIEW,
                    "DB album id is not present in the Flickr remote snapshot",
                    Map.of("externalAlbumId", desiredAlbumId)
            );
            return;
        }

        HostedAlbum remoteAlbum = remote.getAlbum();
        if (!sameText(desiredAlbum.getDesiredTitle(), remoteAlbum.getTitle())
                || !sameText(desiredAlbum.getDesiredDescription(), remoteAlbum.getDescription())) {
            actions.add(
                    SyncActionType.UPDATE_ALBUM_METADATA,
                    SyncActionSafety.SAFE_AUTOMATIC,
                    "Update Flickr album title or description",
                    Map.of(
                            "externalAlbumId", desiredAlbumId,
                            "desiredTitle", value(desiredAlbum.getDesiredTitle()),
                            "remoteTitle", value(remoteAlbum.getTitle()),
                            "desiredDescription", value(desiredAlbum.getDesiredDescription()),
                            "remoteDescription", value(remoteAlbum.getDescription())
                    )
            );
        }
    }

    private void planPhotoActions(
            ImageHostingDesiredStateSnapshot desiredState,
            ImageHostingRemoteSnapshot remote,
            PlanActionBuilder actions
    ) {
        for (DesiredImageHostingPhoto desiredPhoto : desiredState.getPhotos()) {
            String photoId = desiredPhoto.getExternalServiceImageId();
            if (!hasText(photoId)) {
                actions.addPhotoAction(
                        SyncActionType.UPLOAD_PHOTO,
                        SyncActionSafety.SAFE_AUTOMATIC,
                        desiredPhoto,
                        "Upload DB photo that does not have a Flickr photo id",
                        Map.of(
                                "desiredTitle", photoTitle(desiredPhoto),
                                "desiredDescription", photoDescription(desiredPhoto)
                        )
                );
                continue;
            }

            if (requiresMetadataUpdate(desiredPhoto)) {
                actions.addPhotoAction(
                        SyncActionType.UPDATE_PHOTO_METADATA,
                        SyncActionSafety.SAFE_AUTOMATIC,
                        desiredPhoto,
                        "Update Flickr photo metadata because the DB metadata hash changed",
                        Map.of(
                                "externalServiceImageId", photoId,
                                "metadataHash", value(metadataHash(desiredPhoto)),
                                "metadataHashAtSync", value(metadataHashAtSync(desiredPhoto)),
                                "desiredTitle", photoTitle(desiredPhoto),
                                "desiredDescription", photoDescription(desiredPhoto)
                        )
                );
            }
        }
    }

    private void planMembershipActions(
            ImageHostingDesiredStateSnapshot desiredState,
            ImageHostingRemoteSnapshot remote,
            PlanActionBuilder actions
    ) {
        if (!remote.isAlbumFound()) {
            return;
        }

        List<String> desiredPhotoIds = desiredPhotoIds(desiredState.getPhotos());
        if (desiredPhotoIds.isEmpty()) {
            return;
        }

        List<String> remotePhotoIds = remotePhotoIds(remote.getPhotos());
        Set<String> desiredPhotoIdSet = new LinkedHashSet<>(desiredPhotoIds);
        Set<String> remotePhotoIdSet = new LinkedHashSet<>(remotePhotoIds);
        List<String> remoteOnlyPhotoIds = difference(remotePhotoIdSet, desiredPhotoIdSet);
        List<String> desiredOnlyPhotoIds = difference(desiredPhotoIdSet, remotePhotoIdSet);
        String desiredPrimaryPhotoId = desiredPrimaryPhotoId(desiredState.getPhotos()).orElse(null);
        String remotePrimaryPhotoId = remote.getAlbum().getPrimaryPhotoId();

        boolean membershipChanged = !desiredPhotoIds.equals(remotePhotoIds);
        boolean primaryChanged = hasText(desiredPrimaryPhotoId)
                && !Objects.equals(desiredPrimaryPhotoId, remotePrimaryPhotoId);
        if (!membershipChanged && !primaryChanged) {
            return;
        }

        SyncActionType actionType = membershipChanged
                ? SyncActionType.UPDATE_ALBUM_MEMBERSHIP
                : SyncActionType.FIX_PRIMARY_PHOTO;
        SyncActionSafety safety = remoteOnlyPhotoIds.isEmpty()
                ? SyncActionSafety.SAFE_AUTOMATIC
                : SyncActionSafety.REQUIRES_REVIEW;
        actions.add(
                actionType,
                safety,
                membershipChanged
                        ? "Update Flickr album membership to match DB desired state"
                        : "Update Flickr album primary photo to match DB desired state",
                Map.of(
                        "desiredPhotoIds", String.join(",", desiredPhotoIds),
                        "remotePhotoIds", String.join(",", remotePhotoIds),
                        "desiredOnlyPhotoIds", String.join(",", desiredOnlyPhotoIds),
                        "remoteOnlyPhotoIds", String.join(",", remoteOnlyPhotoIds),
                        "desiredPrimaryPhotoId", value(desiredPrimaryPhotoId),
                        "remotePrimaryPhotoId", value(remotePrimaryPhotoId)
                )
        );
    }

    private SyncPlan buildPlan(ImageHostingDesiredStateSnapshot desiredState, PlanActionBuilder actions) {
        return SyncPlan.builder()
                .planId("image-hosting-%s-%s-%s".formatted(
                        value(desiredState.getProvider()),
                        value(desiredState.getItemInventoryId()),
                        UUID.randomUUID()
                ))
                .mode(SyncPlanMode.DRY_RUN)
                .actions(actions.actions())
                .build();
    }

    private ImageHostingRemoteSnapshot emptyRemoteSnapshot(ImageHostingDesiredStateSnapshot desiredState) {
        return ImageHostingRemoteSnapshot.builder()
                .provider(desiredState.getProvider())
                .externalServiceId(desiredState.getExternalServiceId())
                .build();
    }

    private List<String> desiredPhotoIds(List<DesiredImageHostingPhoto> photos) {
        return photos.stream()
                .map(DesiredImageHostingPhoto::getExternalServiceImageId)
                .filter(this::hasText)
                .toList();
    }

    private List<String> remotePhotoIds(List<HostedPhoto> photos) {
        return photos.stream()
                .map(HostedPhoto::getId)
                .filter(this::hasText)
                .toList();
    }

    private Optional<String> desiredPrimaryPhotoId(List<DesiredImageHostingPhoto> photos) {
        return photos.stream()
                .filter(DesiredImageHostingPhoto::isPrimary)
                .map(DesiredImageHostingPhoto::getExternalServiceImageId)
                .filter(this::hasText)
                .findFirst();
    }

    private List<String> difference(Collection<String> left, Collection<String> right) {
        return left.stream()
                .filter(value -> !right.contains(value))
                .toList();
    }

    private boolean requiresMetadataUpdate(DesiredImageHostingPhoto desiredPhoto) {
        String metadataHash = metadataHash(desiredPhoto);
        return hasText(metadataHash) && !Objects.equals(metadataHash, metadataHashAtSync(desiredPhoto));
    }

    private String metadataHash(DesiredImageHostingPhoto desiredPhoto) {
        return Optional.ofNullable(desiredPhoto.getInventoryPhoto())
                .map(ItemInventoryPhoto::getMetadataHash)
                .orElse(null);
    }

    private String metadataHashAtSync(DesiredImageHostingPhoto desiredPhoto) {
        return desiredPhoto.externalImageOptional()
                .map(ExternalImage::getMetadataHashAtSync)
                .orElse(null);
    }

    private String photoTitle(DesiredImageHostingPhoto desiredPhoto) {
        return Optional.ofNullable(desiredPhoto.getInventoryPhoto())
                .map(publishingPolicy::photoTitle)
                .orElse("");
    }

    private String photoDescription(DesiredImageHostingPhoto desiredPhoto) {
        return Optional.ofNullable(desiredPhoto.getInventoryPhoto())
                .map(publishingPolicy::photoDescription)
                .orElse(photoTitle(desiredPhoto));
    }

    private boolean sameText(String left, String right) {
        // The provider returns web addresses as HTML links and some characters as entities.
        return HostedTextComparison.sameText(left, right);
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private class PlanActionBuilder {
        private final ImageHostingDesiredStateSnapshot desiredState;
        private final AtomicInteger sequence = new AtomicInteger();
        private final List<SyncAction> actions = new ArrayList<>();

        private PlanActionBuilder(ImageHostingDesiredStateSnapshot desiredState) {
            this.desiredState = desiredState;
        }

        private void add(
                SyncActionType type,
                SyncActionSafety safety,
                String description,
                Map<String, String> attributes
        ) {
            actions.add(actionBuilder(type, safety, description, attributes).build());
        }

        private void addPhotoAction(
                SyncActionType type,
                SyncActionSafety safety,
                DesiredImageHostingPhoto desiredPhoto,
                String description,
                Map<String, String> attributes
        ) {
            SyncAction.SyncActionBuilder builder = actionBuilder(type, safety, description, attributes)
                    .photoId(desiredPhoto.getExternalServiceImageId())
                    .filename(filename(desiredPhoto));
            builder.attributes(photoAttributes(desiredPhoto));
            actions.add(builder.build());
        }

        private SyncAction.SyncActionBuilder actionBuilder(
                SyncActionType type,
                SyncActionSafety safety,
                String description,
                Map<String, String> attributes
        ) {
            DesiredImageHostingAlbum album = desiredState.getAlbum();
            return SyncAction.builder()
                    .actionId("%03d-%s".formatted(sequence.incrementAndGet(), type.name().toLowerCase().replace('_', '-')))
                    .type(type)
                    .safety(safety)
                    .albumId(album == null ? null : album.getExternalAlbumId())
                    .description(description)
                    .attributes(baseAttributes())
                    .attributes(attributes);
        }

        private Map<String, String> baseAttributes() {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("provider", value(desiredState.getProvider()));
            attributes.put("externalServiceId", value(desiredState.getExternalServiceId()));
            attributes.put("itemInventoryId", value(desiredState.getItemInventoryId()));
            desiredState.albumOptional()
                    .map(DesiredImageHostingAlbum::getExternalImageAlbumId)
                    .ifPresent(externalImageAlbumId -> attributes.put("externalImageAlbumId", value(externalImageAlbumId)));
            return attributes;
        }

        private Map<String, String> photoAttributes(DesiredImageHostingPhoto desiredPhoto) {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("itemInventoryPhotoId", value(desiredPhoto.getItemInventoryPhotoId()));
            Optional.ofNullable(desiredPhoto.getExternalImageId())
                    .ifPresent(externalImageId -> attributes.put("externalImageId", value(externalImageId)));
            desiredPhoto.albumMembershipOptional()
                    .map(ExternalImageAlbumImage::getSortOrder)
                    .ifPresent(sortOrder -> attributes.put("sortOrder", value(sortOrder)));
            attributes.put("primary", Boolean.toString(desiredPhoto.isPrimary()));
            return attributes;
        }

        private String filename(DesiredImageHostingPhoto desiredPhoto) {
            return Optional.ofNullable(desiredPhoto.getInventoryPhoto())
                    .map(ItemInventoryPhoto::getFileName)
                    .orElse(null);
        }

        private List<SyncAction> actions() {
            return actions;
        }
    }
}
