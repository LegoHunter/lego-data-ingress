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
import io.legohunter.egress.imagehosting.plan.ImageHostingSyncPlanRequest;
import io.legohunter.egress.imagehosting.plan.ImageHostingSyncPlanService;
import io.legohunter.imaging.model.PhotoServiceErrorType;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.model.SimplePhotoServiceRequest;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.ingress.s3.api.S3Service;
import io.legohunter.lego.data.environment.LegoEnvironmentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Removes an item's photos from the image host when they no longer belong to it (HOMELAB-113).
 *
 * <p>The database is the system of record and the image host holds a published copy, so a photo that leaves the
 * database must leave the host too. Two callers:
 * <ul>
 *   <li>A photo is deleted by removing its object from the final-photo bucket: {@link #unpublishPhoto} then
 *       {@link #reconcileAlbumAfterRemoval}, around the deletion of the photo row. Every environment.</li>
 *   <li>A lower-environment item is being torn down: {@link #purgeItem} removes all its photos, its album and its
 *       stored photo files. Never in production.</li>
 * </ul>
 * The host deletes an album itself when its last photo is deleted, so no album is deleted explicitly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HostedContentRemovalService {
    private final Optional<ImageHostingService> imageHostingService;
    private final ItemInventoryPhotoDao itemInventoryPhotoDao;
    private final ExternalImageDao externalImageDao;
    private final ExternalImageAlbumDao externalImageAlbumDao;
    private final ExternalImageAlbumImageDao externalImageAlbumImageDao;
    private final ImageHostingSyncProperties properties;
    private final ImageHostingRetryTemplate retryTemplate;
    private final ImageHostingSyncPlanService syncPlanService;
    private final ImageHostingSyncPlanExecutor syncPlanExecutor;
    private final S3Service s3Service;
    private final LegoEnvironmentProperties environment;

    /**
     * Deletes the photo's published copy and the rows that link the photo to it. Call this before deleting the
     * photo row, which those rows reference. A copy the host no longer has counts as deleted.
     *
     * @return the hosted photo ids that were removed; empty when the photo was never published
     * @throws IllegalStateException when the host refuses the deletion, so the photo row is kept and the event
     *                               can be retried
     */
    public List<String> unpublishPhoto(ItemInventoryPhoto photo) {
        Integer externalServiceId = externalServiceId();
        List<String> removed = new ArrayList<>();
        for (ExternalImage image : externalImageDao.findByItemInventoryPhotoId(photo.getItemInventoryPhotoId())) {
            if (!Objects.equals(externalServiceId, image.getExternalServiceId())) {
                throw new IllegalStateException(
                        "Photo [%s] is published to external service [%s], which image hosting does not manage"
                                .formatted(photo.getItemInventoryPhotoId(), image.getExternalServiceId())
                );
            }
            if (hasText(image.getExternalServiceImageId())) {
                deleteHostedPhoto(image.getExternalServiceImageId());
                removed.add(image.getExternalServiceImageId());
            }
            externalImageAlbumImageDao.deleteByExternalImageId(image.getExternalImageId());
            externalImageDao.deleteByExternalImageId(image.getExternalImageId());
            log.info(
                    "image_hosting.removal.photo_unpublished itemInventoryId={} itemInventoryPhotoId={} hostedPhotoId={}",
                    photo.getItemInventoryId(),
                    photo.getItemInventoryPhotoId(),
                    image.getExternalServiceImageId()
            );
        }
        return removed;
    }

    /**
     * After a photo row is gone: when the item has no photos left, the host has already dropped the empty album,
     * so the album row goes too; otherwise the album is brought back in step (its description lists captions).
     * A failure here marks the album FAILED for the scheduled job to retry; it never undoes the deletion.
     */
    public void reconcileAlbumAfterRemoval(Integer itemInventoryId) {
        Optional<ExternalImageAlbum> existing =
                externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(externalServiceId(), itemInventoryId);
        if (existing.isEmpty()) {
            return;
        }
        ExternalImageAlbum album = existing.get();
        if (itemInventoryPhotoDao.findByItemInventoryId(itemInventoryId).isEmpty()) {
            deleteAlbumRows(album);
            log.info(
                    "image_hosting.removal.album_removed itemInventoryId={} hostedAlbumId={} reason=no_photos_left",
                    itemInventoryId,
                    album.getExternalAlbumId()
            );
            return;
        }
        try {
            SyncPlan plan = syncPlanService.plan(ImageHostingSyncPlanRequest.builder()
                    .itemInventoryId(itemInventoryId)
                    .externalServiceId(album.getExternalServiceId())
                    .build());
            if (plan.hasActions()) {
                syncPlanExecutor.execute(plan, false);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "image_hosting.removal.album_reconcile_failed itemInventoryId={} hostedAlbumId={} message={}",
                    itemInventoryId,
                    album.getExternalAlbumId(),
                    e.getMessage(),
                    e
            );
            album.setSyncStatus(ExternalSyncStatus.FAILED);
            album.setErrorMessage("Album was not brought back in step after a photo was removed: " + e.getMessage());
            album.setLastSyncedAt(ZonedDateTime.now());
            externalImageAlbumDao.update(album);
        }
    }

    /**
     * What removing every photo of a lower-environment item involves and, with {@code apply}, the removal itself:
     * each photo's published copy, its rows, its stored file, then the album rows. Photos are handled one at a
     * time, so a run that stops part-way can simply be repeated.
     */
    public ItemPurgeReport purgeItem(Integer itemInventoryId, boolean apply) {
        if (itemInventoryId == null) {
            throw new IllegalArgumentException("itemInventoryId is required");
        }
        requireLowerEnvironment("remove an item's photos and album");

        Integer externalServiceId = externalServiceId();
        List<ItemInventoryPhoto> photos = itemInventoryPhotoDao.findByItemInventoryId(itemInventoryId).stream()
                .sorted(Comparator.comparing(
                        ItemInventoryPhoto::getItemInventoryPhotoId,
                        Comparator.nullsLast(Integer::compareTo)
                ))
                .toList();
        Optional<ExternalImageAlbum> album =
                externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(externalServiceId, itemInventoryId);

        List<ItemPurgeReport.PurgedPhoto> purged = new ArrayList<>();
        for (ItemInventoryPhoto photo : photos) {
            List<String> hostedPhotoIds = externalImageDao.findByItemInventoryPhotoId(photo.getItemInventoryPhotoId())
                    .stream()
                    .map(ExternalImage::getExternalServiceImageId)
                    .filter(this::hasText)
                    .toList();
            if (apply) {
                unpublishPhoto(photo);
                itemInventoryPhotoDao.deleteByMd5(photo.getMd5());
                deleteStoredFile(photo);
            }
            purged.add(new ItemPurgeReport.PurgedPhoto(
                    photo.getItemInventoryPhotoId(),
                    photo.getFileName(),
                    hostedPhotoIds,
                    photo.getS3Bucket(),
                    photo.getS3Key()
            ));
        }
        if (apply) {
            album.ifPresent(this::deleteAlbumRows);
            log.info(
                    "image_hosting.removal.item_purged itemInventoryId={} photos={} hostedAlbumId={}",
                    itemInventoryId,
                    photos.size(),
                    album.map(ExternalImageAlbum::getExternalAlbumId).orElse(null)
            );
        }
        return new ItemPurgeReport(
                itemInventoryId,
                environment.getCode(),
                apply,
                album.map(ExternalImageAlbum::getExternalAlbumId).orElse(null),
                album.map(ExternalImageAlbum::getAlbumUrl).orElse(null),
                purged
        );
    }

    void requireLowerEnvironment(String what) {
        if (environment.isProduction()) {
            throw new ProductionRemovalRefusedException(
                    "Refusing to %s in production: this is a lower-environment operation".formatted(what)
            );
        }
    }

    private void deleteHostedPhoto(String hostedPhotoId) {
        ImageHostingService hosting = imageHostingService.orElseThrow(() -> new IllegalStateException(
                "Hosted photo [%s] cannot be deleted: no image hosting service is configured".formatted(hostedPhotoId)
        ));
        PhotoServiceResponse<Void> response = retryTemplate.execute(
                "deletePhoto",
                () -> hosting.deletePhoto(new SimplePhotoServiceRequest<>(hostedPhotoId))
        ).response();
        if (response.isError() && response.errorType() != PhotoServiceErrorType.PHOTO_NOT_FOUND) {
            throw new IllegalStateException(
                    "Image host refused to delete photo [%s]: %s".formatted(hostedPhotoId, response.responseMessage())
            );
        }
    }

    private void deleteAlbumRows(ExternalImageAlbum album) {
        externalImageAlbumImageDao.deleteByExternalImageAlbumId(album.getExternalImageAlbumId());
        externalImageAlbumDao.deleteByExternalImageAlbumId(album.getExternalImageAlbumId());
    }

    private void deleteStoredFile(ItemInventoryPhoto photo) {
        if (!hasText(photo.getS3Bucket()) || !hasText(photo.getS3Key())) {
            return;
        }
        try {
            s3Service.deleteObject(photo.getS3Bucket(), photo.getS3Key());
        } catch (RuntimeException e) {
            // The photo row is already gone; a file left behind is harmless and is reported, not fatal.
            log.warn(
                    "image_hosting.removal.stored_file_not_deleted bucket={} key={} message={}",
                    photo.getS3Bucket(),
                    photo.getS3Key(),
                    e.getMessage()
            );
        }
    }

    private Integer externalServiceId() {
        return properties.resolveProvider(null, null).externalServiceId();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
