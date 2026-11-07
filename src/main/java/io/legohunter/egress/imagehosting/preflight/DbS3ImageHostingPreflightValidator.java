package io.legohunter.egress.imagehosting.preflight;

import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.egress.imagehosting.ImageHostingSyncRequest;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingAlbum;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingPhoto;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateSnapshot;
import io.legohunter.ingress.s3.api.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DbS3ImageHostingPreflightValidator implements ImageHostingPreflightValidator {
    private final S3Service s3Service;

    @Override
    public ImageHostingPreflightResult validate(ImageHostingDesiredStateSnapshot snapshot, ImageHostingSyncRequest request) {
        List<ImageHostingPreflightIssue> issues = new ArrayList<>();
        if (snapshot == null) {
            issues.add(issue(ImageHostingPreflightIssueType.SNAPSHOT_REQUIRED, null, null, "Image hosting desired state snapshot is required"));
            return ImageHostingPreflightResult.withIssues(issues);
        }

        Integer itemInventoryId = snapshot.getItemInventoryId();
        if (snapshot.getInventory() == null) {
            issues.add(issue(ImageHostingPreflightIssueType.ITEM_INVENTORY_REQUIRED, null, null, "Item inventory is required"));
        } else if (itemInventoryId == null) {
            issues.add(issue(ImageHostingPreflightIssueType.ITEM_INVENTORY_ID_MISSING, null, null, "Item inventory id is required"));
        }

        validateAlbum(snapshot, issues);
        validatePhotos(snapshot, issues);
        validateMembership(snapshot, issues);
        return ImageHostingPreflightResult.withIssues(issues);
    }

    private void validateAlbum(ImageHostingDesiredStateSnapshot snapshot, List<ImageHostingPreflightIssue> issues) {
        DesiredImageHostingAlbum album = snapshot.getAlbum();
        if (album == null) {
            return;
        }

        if (!hasText(album.getDesiredTitle())) {
            issues.add(issue(
                    ImageHostingPreflightIssueType.ALBUM_TITLE_MISSING,
                    snapshot.getItemInventoryId(),
                    null,
                    "Desired album title is required"
            ));
        }
        if (!hasText(album.getDesiredDescription())) {
            issues.add(issue(
                    ImageHostingPreflightIssueType.ALBUM_DESCRIPTION_MISSING,
                    snapshot.getItemInventoryId(),
                    null,
                    "Desired album description is required"
            ));
        }

        ExternalImageAlbum externalAlbum = album.getExternalAlbum();
        if (externalAlbum != null
                && hasText(externalAlbum.getExternalAlbumId())
                && externalAlbum.getExternalImageAlbumId() == null) {
            issues.add(ImageHostingPreflightIssue.builder()
                    .type(ImageHostingPreflightIssueType.EXTERNAL_IMAGE_ALBUM_ID_MISSING)
                    .itemInventoryId(snapshot.getItemInventoryId())
                    .message("External image album row id is required when a hosted album id is present")
                    .build());
        }
    }

    private void validatePhotos(ImageHostingDesiredStateSnapshot snapshot, List<ImageHostingPreflightIssue> issues) {
        List<DesiredImageHostingPhoto> photos = snapshot.getPhotos();
        if (photos.isEmpty()) {
            return;
        }

        long primaryCount = photos.stream()
                .filter(DesiredImageHostingPhoto::isPrimary)
                .count();
        if (primaryCount == 0) {
            issues.add(issue(
                    ImageHostingPreflightIssueType.PRIMARY_PHOTO_MISSING,
                    snapshot.getItemInventoryId(),
                    null,
                    "One primary photo is required before syncing image hosting membership"
            ));
        } else if (primaryCount > 1) {
            issues.add(issue(
                    ImageHostingPreflightIssueType.MULTIPLE_PRIMARY_PHOTOS,
                    snapshot.getItemInventoryId(),
                    null,
                    "Only one primary photo can be synced to image hosting membership"
            ));
        }

        photos.forEach(photo -> validatePhoto(snapshot, photo, issues));
        duplicateExternalPhotoIds(photos).forEach(photoId -> issues.add(issue(
                ImageHostingPreflightIssueType.DUPLICATE_EXTERNAL_SERVICE_IMAGE_ID,
                snapshot.getItemInventoryId(),
                null,
                "Duplicate hosted photo id [%s] appears in DB desired state".formatted(photoId)
        )));
    }

    private void validatePhoto(
            ImageHostingDesiredStateSnapshot snapshot,
            DesiredImageHostingPhoto photo,
            List<ImageHostingPreflightIssue> issues
    ) {
        if (photo == null || photo.getInventoryPhoto() == null) {
            issues.add(issue(
                    ImageHostingPreflightIssueType.PHOTO_REQUIRED,
                    snapshot.getItemInventoryId(),
                    null,
                    "Inventory photo is required"
            ));
            return;
        }

        ItemInventoryPhoto inventoryPhoto = photo.getInventoryPhoto();
        Integer itemInventoryPhotoId = inventoryPhoto.getItemInventoryPhotoId();
        if (itemInventoryPhotoId == null) {
            issues.add(issue(
                    ImageHostingPreflightIssueType.PHOTO_ID_MISSING,
                    snapshot.getItemInventoryId(),
                    null,
                    "Inventory photo id is required"
            ));
        }
        if (!Objects.equals(snapshot.getItemInventoryId(), inventoryPhoto.getItemInventoryId())) {
            issues.add(issue(
                    ImageHostingPreflightIssueType.PHOTO_INVENTORY_MISMATCH,
                    snapshot.getItemInventoryId(),
                    itemInventoryPhotoId,
                    "Inventory photo belongs to itemInventoryId [%s] but snapshot is for itemInventoryId [%s]"
                            .formatted(inventoryPhoto.getItemInventoryId(), snapshot.getItemInventoryId())
            ));
        }

        if (!photo.hasS3Object()) {
            issues.add(issue(
                    ImageHostingPreflightIssueType.S3_LOCATION_MISSING,
                    snapshot.getItemInventoryId(),
                    itemInventoryPhotoId,
                    "Inventory photo [%s] must have S3 bucket and key before image hosting sync".formatted(itemInventoryPhotoId)
            ));
        } else if (!s3Service.objectExists(inventoryPhoto.getS3Bucket(), inventoryPhoto.getS3Key())) {
            issues.add(issue(
                    ImageHostingPreflightIssueType.S3_OBJECT_MISSING,
                    snapshot.getItemInventoryId(),
                    itemInventoryPhotoId,
                    "S3 object [%s/%s] is not available for inventory photo [%s]"
                            .formatted(inventoryPhoto.getS3Bucket(), inventoryPhoto.getS3Key(), itemInventoryPhotoId)
            ));
        }

        ExternalImage externalImage = photo.getExternalImage();
        if (externalImage != null
                && hasText(externalImage.getExternalServiceImageId())
                && externalImage.getExternalImageId() == null) {
            issues.add(ImageHostingPreflightIssue.builder()
                    .type(ImageHostingPreflightIssueType.EXTERNAL_IMAGE_ID_MISSING)
                    .itemInventoryId(snapshot.getItemInventoryId())
                    .itemInventoryPhotoId(itemInventoryPhotoId)
                    .message("External image row id is required when hosted photo id [%s] is present"
                            .formatted(externalImage.getExternalServiceImageId()))
                    .build());
        }
    }

    private Collection<String> duplicateExternalPhotoIds(List<DesiredImageHostingPhoto> photos) {
        return photos.stream()
                .map(DesiredImageHostingPhoto::getExternalServiceImageId)
                .filter(this::hasText)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
    }

    private void validateMembership(ImageHostingDesiredStateSnapshot snapshot, List<ImageHostingPreflightIssue> issues) {
        Set<Long> desiredExternalImageIds = snapshot.getPhotos()
                .stream()
                .map(DesiredImageHostingPhoto::getExternalImageId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (ExternalImageAlbumImage membership : snapshot.getAlbumMemberships()) {
            if (membership.getExternalImageId() == null) {
                issues.add(ImageHostingPreflightIssue.builder()
                        .type(ImageHostingPreflightIssueType.MEMBERSHIP_EXTERNAL_IMAGE_ID_MISSING)
                        .itemInventoryId(snapshot.getItemInventoryId())
                        .externalImageAlbumId(membership.getExternalImageAlbumId())
                        .message("Album membership row must reference an external image id")
                        .build());
            } else if (!desiredExternalImageIds.contains(membership.getExternalImageId())) {
                issues.add(ImageHostingPreflightIssue.builder()
                        .type(ImageHostingPreflightIssueType.MEMBERSHIP_REFERENCES_UNEXPECTED_IMAGE)
                        .itemInventoryId(snapshot.getItemInventoryId())
                        .externalImageAlbumId(membership.getExternalImageAlbumId())
                        .externalImageId(membership.getExternalImageId())
                        .message("Album membership references external image [%s] that is not part of the DB desired state"
                                .formatted(membership.getExternalImageId()))
                        .build());
            }
        }
    }

    private ImageHostingPreflightIssue issue(
            ImageHostingPreflightIssueType type,
            Integer itemInventoryId,
            Integer itemInventoryPhotoId,
            String message
    ) {
        return ImageHostingPreflightIssue.builder()
                .type(type)
                .itemInventoryId(itemInventoryId)
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .message(message)
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
