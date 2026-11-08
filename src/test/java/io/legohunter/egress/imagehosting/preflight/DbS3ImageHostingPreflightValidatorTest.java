package io.legohunter.egress.imagehosting.preflight;

import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.egress.imagehosting.ImageHostingSyncRequest;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingAlbum;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingPhoto;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateSnapshot;
import io.legohunter.ingress.s3.api.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static io.legohunter.egress.imagehosting.preflight.ImageHostingPreflightIssueType.DUPLICATE_EXTERNAL_SERVICE_IMAGE_ID;
import static io.legohunter.egress.imagehosting.preflight.ImageHostingPreflightIssueType.MEMBERSHIP_REFERENCES_UNEXPECTED_IMAGE;
import static io.legohunter.egress.imagehosting.preflight.ImageHostingPreflightIssueType.MULTIPLE_PRIMARY_PHOTOS;
import static io.legohunter.egress.imagehosting.preflight.ImageHostingPreflightIssueType.PRIMARY_PHOTO_MISSING;
import static io.legohunter.egress.imagehosting.preflight.ImageHostingPreflightIssueType.S3_LOCATION_MISSING;
import static io.legohunter.egress.imagehosting.preflight.ImageHostingPreflightIssueType.S3_OBJECT_MISSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DbS3ImageHostingPreflightValidatorTest {
    private static final ImageHostingSyncRequest REQUEST = ImageHostingSyncRequest.builder()
            .itemInventoryId(100)
            .provider("flickr")
            .externalServiceId(10)
            .build();

    @Mock
    private S3Service s3Service;

    private DbS3ImageHostingPreflightValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DbS3ImageHostingPreflightValidator(s3Service);
    }

    @Test
    void validate_acceptsDbDesiredStateWhenPrimaryAndS3ObjectsAreValid() {
        DesiredImageHostingPhoto photo = desiredPhoto(photo(11, true), externalImage(201L, 11, "flickr-photo-11"));
        when(s3Service.objectExists("photos", "100/11.jpg")).thenReturn(true);

        ImageHostingPreflightResult result = validator.validate(snapshot(List.of(photo)), REQUEST);

        assertThat(result.isValid()).isTrue();
        assertThat(result.issues()).isEmpty();
        verify(s3Service).objectExists("photos", "100/11.jpg");
    }

    @Test
    void validate_requiresExactlyOnePrimaryPhotoWhenPhotosExist() {
        DesiredImageHostingPhoto first = desiredPhoto(photo(11, false), null);
        DesiredImageHostingPhoto second = desiredPhoto(photo(12, false), null);
        when(s3Service.objectExists("photos", "100/11.jpg")).thenReturn(true);
        when(s3Service.objectExists("photos", "100/12.jpg")).thenReturn(true);

        ImageHostingPreflightResult missingPrimary = validator.validate(snapshot(List.of(first, second)), REQUEST);

        assertThat(missingPrimary.issues())
                .extracting(ImageHostingPreflightIssue::getType)
                .contains(PRIMARY_PHOTO_MISSING);

        DesiredImageHostingPhoto primaryOne = desiredPhoto(photo(11, true), null);
        DesiredImageHostingPhoto primaryTwo = desiredPhoto(photo(12, true), null);
        ImageHostingPreflightResult multiplePrimary = validator.validate(snapshot(List.of(primaryOne, primaryTwo)), REQUEST);

        assertThat(multiplePrimary.issues())
                .extracting(ImageHostingPreflightIssue::getType)
                .contains(MULTIPLE_PRIMARY_PHOTOS);
    }

    @Test
    void validate_reportsMissingS3LocationWithoutCallingObjectStore() {
        ItemInventoryPhoto photo = photo(11, true);
        photo.setS3Key(null);

        ImageHostingPreflightResult result = validator.validate(snapshot(List.of(desiredPhoto(photo, null))), REQUEST);

        assertThat(result.issues())
                .extracting(ImageHostingPreflightIssue::getType)
                .contains(S3_LOCATION_MISSING);
        verify(s3Service, never()).objectExists("photos", null);
    }

    @Test
    void validate_reportsMissingS3ObjectForInventoryPhoto() {
        DesiredImageHostingPhoto photo = desiredPhoto(photo(11, true), null);
        when(s3Service.objectExists("photos", "100/11.jpg")).thenReturn(false);

        ImageHostingPreflightResult result = validator.validate(snapshot(List.of(photo)), REQUEST);

        assertThat(result.issues())
                .extracting(ImageHostingPreflightIssue::getType)
                .contains(S3_OBJECT_MISSING);
        assertThat(result.issues())
                .extracting(ImageHostingPreflightIssue::getItemInventoryPhotoId)
                .contains(11);
    }

    @Test
    void validate_reportsDuplicateHostedPhotoIdsAndUnexpectedMembershipRows() {
        DesiredImageHostingPhoto first = desiredPhoto(photo(11, true), externalImage(201L, 11, "flickr-photo"));
        DesiredImageHostingPhoto second = desiredPhoto(photo(12, false), externalImage(202L, 12, "flickr-photo"));
        when(s3Service.objectExists("photos", "100/11.jpg")).thenReturn(true);
        when(s3Service.objectExists("photos", "100/12.jpg")).thenReturn(true);

        ImageHostingPreflightResult result = validator.validate(
                snapshot(
                        List.of(first, second),
                        List.of(ExternalImageAlbumImage.builder()
                                .externalImageAlbumId(301L)
                                .externalImageId(999L)
                                .build())
                ),
                REQUEST
        );

        assertThat(result.issues())
                .extracting(ImageHostingPreflightIssue::getType)
                .contains(DUPLICATE_EXTERNAL_SERVICE_IMAGE_ID, MEMBERSHIP_REFERENCES_UNEXPECTED_IMAGE);
    }

    private static ImageHostingDesiredStateSnapshot snapshot(List<DesiredImageHostingPhoto> photos) {
        return snapshot(photos, List.of());
    }

    private static ImageHostingDesiredStateSnapshot snapshot(
            List<DesiredImageHostingPhoto> photos,
            List<ExternalImageAlbumImage> memberships
    ) {
        return ImageHostingDesiredStateSnapshot.builder()
                .provider("flickr")
                .externalServiceId(10)
                .inventory(inventory())
                .album(DesiredImageHostingAlbum.builder()
                        .desiredTitle("4558-1 - Metroliner")
                        .desiredDescription("Inventory item [inventory-uuid]")
                        .externalAlbum(ExternalImageAlbum.builder()
                                .externalImageAlbumId(301L)
                                .externalAlbumId("flickr-album-100")
                                .build())
                        .build())
                .photos(photos)
                .albumMemberships(memberships)
                .build();
    }

    private static DesiredImageHostingPhoto desiredPhoto(ItemInventoryPhoto photo, ExternalImage externalImage) {
        return DesiredImageHostingPhoto.builder()
                .inventoryPhoto(photo)
                .externalImage(externalImage)
                .build();
    }

    private static ItemInventory inventory() {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(100);
        inventory.setUuid("inventory-uuid");
        return inventory;
    }

    private static ItemInventoryPhoto photo(Integer itemInventoryPhotoId, boolean primary) {
        return ItemInventoryPhoto.builder()
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .itemInventoryId(100)
                .s3Bucket("photos")
                .s3Key("100/%s.jpg".formatted(itemInventoryPhotoId))
                .primary(primary)
                .build();
    }

    private static ExternalImage externalImage(Long externalImageId, Integer itemInventoryPhotoId, String externalServiceImageId) {
        return ExternalImage.builder()
                .externalImageId(externalImageId)
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .externalServiceImageId(externalServiceImageId)
                .build();
    }
}
