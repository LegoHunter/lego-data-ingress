package io.legohunter.ingress.source.photo.service;

import io.legohunter.imaging.exception.PhotoProcessingException;
import io.legohunter.imaging.metadata.MetadataFingerprintService;
import io.legohunter.imaging.metadata.impl.MetadataExtractorService;
import io.legohunter.imaging.metadata.model.ConditionEnum;
import io.legohunter.imaging.metadata.model.ImageMetadata;
import io.legohunter.imaging.scaling.ImageScalingService;
import io.legohunter.ingress.common.logging.LoggingContext;
import io.legohunter.ingress.config.storage.S3BucketProperties;
import io.legohunter.ingress.s3.api.S3Service;
import io.legohunter.ingress.s3.exception.S3ObjectNotFoundException;
import io.legohunter.ingress.source.photo.metrics.PhotoMetricsService;
import io.legohunter.ingress.source.photo.model.PhotoUploadEvent;
import io.legohunter.ingress.util.exception.Unchecked;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryExternalCatalogItemDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryExternalCatalogItem;
import io.legohunter.data.dto.ItemInventoryPhoto;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static io.legohunter.data.enums.PhotoStatus.PROCESSED;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoProcessingService {

    private static final String PHOTO_UPLOAD_PREFIX = "photos/";
    private static final String REJECTED_PHOTO_PREFIX = "photos/rejected/";
    private static final String DUPLICATE_PHOTO_PREFIX = "photos/duplicate/";
    private static final int BRICKLINK_SERVICE_ID = 2;

    private final S3Service s3Service;
    private final ImageScalingService imageScalingService;
    private final MetadataExtractorService metadataExtractorService;
    private final MetadataFingerprintService metadataFingerprintService;
    private final PhotoMetricsService photoMetricsService;
    private final S3BucketProperties s3BucketProperties;

    private final ItemInventoryDao itemInventoryDao;
    private final ItemInventoryPhotoDao itemInventoryPhotoDao;
    private final ExternalCatalogItemDao externalCatalogItemDao;
    private final ItemInventoryExternalCatalogItemDao itemInventoryExternalCatalogItemDao;

    // =========================================================
    // EVENT INGESTION
    // =========================================================

    public void process(PhotoUploadEvent event) {

        execute(
                event.getObjectKey(),
                "event",
                true,
                () -> s3Service.getObject(
                        event.getBucket(),
                        event.getObjectKey()
                ),
                event
        );
    }

    // =========================================================
    // BATCH INGESTION
    // =========================================================

    public void process(MultipartFile file) {

        execute(
                file.getOriginalFilename(),
                "batch",
                false,
                Unchecked.wrap(file::getInputStream, e -> new PhotoProcessingException("Failed to open multipart file stream", e)),
                null
        );
    }

    // =========================================================
    // EXECUTION WRAPPER
    // =========================================================

    private void execute(
            String filename,
            String mode,
            boolean deleteSource,
            Supplier<InputStream> streamSupplier,
            PhotoUploadEvent event
    ) {

        LoggingContext.init();

        long startTime = System.currentTimeMillis();

        try {

            log.info(
                    "photo.process.start mode={} filename={}",
                    mode,
                    filename
            );

            try (InputStream is = streamSupplier.get()) {

                processInternal(
                        is,
                        filename,
                        deleteSource,
                        event,
                        mode
                );
            }

            photoMetricsService.incrementProcessed(mode);

            log.info(
                    "photo.process.success mode={} filename={}",
                    mode,
                    filename
            );

        } catch (S3ObjectNotFoundException e) {

            photoMetricsService.incrementSkipped(mode, "source_missing");

            log.warn(
                    "photo.process.source_missing mode={} filename={} bucket={} key={} reason=stale_object_event",
                    mode,
                    filename,
                    e.getBucket(),
                    e.getKey()
            );

        } catch (Exception e) {

            photoMetricsService.incrementFailed(mode);

            if (event != null && e instanceof RejectedPhotoUploadException) {
                boolean moved =
                        moveSourceObject(
                        event,
                        REJECTED_PHOTO_PREFIX,
                        "rejected"
                );

                if (moved) {
                    log.warn(
                            "photo.process.rejected mode={} filename={} reason={}",
                            mode,
                            filename,
                            e.getMessage()
                    );
                    return;
                }
            }

            log.error(
                    "photo.process.failed mode={} filename={}",
                    mode,
                    filename,
                    e
            );

            throw new PhotoProcessingException(
                    "Photo processing failed for file [%s]"
                            .formatted(filename),
                    e
            );

        } finally {

            long duration = System.currentTimeMillis() - startTime;

            photoMetricsService.recordProcessingTime(
                    duration,
                    mode
            );

            log.info(
                    "photo.process.complete mode={} durationMs={}",
                    mode,
                    duration
            );

            LoggingContext.clear();
        }
    }

    // =========================================================
    // INTERNAL PIPELINE
    // =========================================================

    private void processInternal(
            InputStream originalStream,
            String originalFilename,
            boolean deleteSource,
            PhotoUploadEvent event,
            String mode
    ) throws Exception {

        MDC.put("filename", originalFilename);

        String normalizedFileName;

        try {
            normalizedFileName =
                    normalizeFileName(originalFilename);
        } catch (Exception e) {
            throw new RejectedPhotoUploadException(
                    "Photo source key [%s] does not contain a processable file name"
                            .formatted(originalFilename),
                    e
            );
        }

        MDC.put("normalizedFileName", normalizedFileName);

        // =========================================================
        // READ ORIGINAL
        // =========================================================

        byte[] originalBytes = originalStream.readAllBytes();

        log.info(
                "photo.read.complete sizeBytes={}",
                originalBytes.length
        );

        // =========================================================
        // EXTRACT METADATA
        // =========================================================

        ImageMetadata metadata;

        try {
            metadata =
                    metadataExtractorService.extractMetadata(originalBytes);
        } catch (Exception e) {
            throw new RejectedPhotoUploadException(
                    "Photo metadata could not be extracted from [%s]"
                            .formatted(originalFilename),
                    e
            );
        }

        String description =
                metadata.caption();

        String metadataHash =
                metadataFingerprintService.calculateHash(metadata);

        String uuid;

        try {
            uuid = metadata.requireUuid();
        } catch (Exception e) {
            throw new RejectedPhotoUploadException(
                    "Photo metadata is missing required uuid for [%s]"
                            .formatted(originalFilename),
                    e
            );
        }

        MDC.put("uuid", uuid);

        String bricklinkItemNumber;

        try {
            bricklinkItemNumber =
                    metadata.requireExternalItemNumber();
        } catch (Exception e) {
            throw new RejectedPhotoUploadException(
                    "Photo metadata is missing required external item number for [%s]"
                            .formatted(originalFilename),
                    e
            );
        }

        MDC.put("bricklink", bricklinkItemNumber);

        log.info(
                "photo.metadata.extracted uuid={} bricklink={} filename={}",
                uuid,
                bricklinkItemNumber,
                normalizedFileName
        );

        // =========================================================
        // SCALE IMAGE
        // =========================================================

        byte[] scaledBytes;

        try {
            scaledBytes =
                    imageScalingService.scale(originalBytes);
        } catch (Exception e) {
            throw new RejectedPhotoUploadException(
                    "Photo image could not be scaled for [%s]"
                            .formatted(originalFilename),
                    e
            );
        }

        log.info(
                "photo.scaled originalSize={} scaledSize={}",
                originalBytes.length,
                scaledBytes.length
        );

        // =========================================================
        // MD5
        // =========================================================

        String md5;

        try {
            md5 =
                    metadataExtractorService.calculateMd5(scaledBytes);
        } catch (Exception e) {
            throw new RejectedPhotoUploadException(
                    "Photo hash could not be calculated for [%s]"
                            .formatted(originalFilename),
                    e
            );
        }

        MDC.put("md5", md5);
        MDC.put("metadataHash", metadataHash);

        log.info(
                "photo.hash.computed md5={} metadataHash={}",
                md5,
                metadataHash
        );

        Optional<ItemInventory> existingItemInventory =
                itemInventoryDao.findByUuid(uuid);

        Optional<ItemInventoryPhoto> existingLogicalPhoto =
                existingItemInventory
                        .map(ItemInventory::getItemInventoryId)
                        .flatMap(itemInventoryId ->
                                itemInventoryPhotoDao.findByItemInventoryIdAndFileName(
                                        itemInventoryId,
                                        normalizedFileName
                                )
                        );

        Optional<ItemInventoryPhoto> existingMd5Owner =
                itemInventoryPhotoDao.findByMd5(md5);

        if (isDuplicateOfDifferentLogicalPhoto(
                existingMd5Owner,
                existingLogicalPhoto
        )) {

            log.info(
                    "photo.duplicate.detected md5={} filename={}",
                    md5,
                    normalizedFileName
            );

            photoMetricsService.incrementDuplicate(mode);

            if (event != null) {
                moveSourceObject(
                        event,
                        DUPLICATE_PHOTO_PREFIX,
                        "duplicate"
                );
            }

            return;
        }

        // =========================================================
        // FIND EXTERNAL ITEM
        // =========================================================

        ExternalCatalogItem externalCatalogItem =
                externalCatalogItemDao.findByExternalServiceIdAndExternalItemKey(
                                BRICKLINK_SERVICE_ID,
                                bricklinkItemNumber
                        )
                        .orElseThrow(() ->
                                new RejectedPhotoUploadException(
                                        "Bricklink item number [%s] extracted from photo [%s] was not found"
                                                .formatted(
                                                        bricklinkItemNumber,
                                                        originalFilename
                                                )
                                )
                        );

        boolean replaceExistingPhoto =
                existingLogicalPhoto.isPresent();

        boolean contentChanged =
                existingLogicalPhoto
                        .map(photo -> !md5.equals(photo.getMd5()))
                        .orElse(true);

        boolean metadataChanged =
                existingLogicalPhoto
                        .map(photo -> !metadataHash.equals(photo.getMetadataHash()))
                        .orElse(true);

        // =========================================================
        // BUILD DB STATE
        // =========================================================

        ItemInventory itemInventory =
                buildItemInventory(
                        uuid,
                        description,
                        metadata,
                        existingItemInventory
                );

        existingItemInventory
                .map(ItemInventory::getItemInventoryId)
                .ifPresent(itemInventory::setItemInventoryId);

        boolean primaryPhoto =
                effectivePrimaryPhoto(metadata, existingLogicalPhoto);

        String photoCaption =
                effectivePhotoCaption(metadata, existingLogicalPhoto);

        if (replaceExistingPhoto
                && !contentChanged
                && !metadataChanged
                && hasNoEffectiveMetadataChanges(
                existingItemInventory.orElseThrow(),
                itemInventory,
                existingLogicalPhoto.orElseThrow(),
                primaryPhoto,
                photoCaption
        )) {

            log.info(
                    "photo.duplicate.detected unchanged logical photo md5={} filename={}",
                    md5,
                    normalizedFileName
            );

            photoMetricsService.incrementDuplicate(mode);

            if (event != null) {
                moveSourceObject(
                        event,
                        DUPLICATE_PHOTO_PREFIX,
                        "duplicate"
                );
            }

            return;
        }

        // =========================================================
        // BUILD STORAGE KEY
        // =========================================================

        String destinationKey =
                buildKey(
                        bricklinkItemNumber,
                        uuid,
                        md5
                );

        // =========================================================
        // UPLOAD TO FINAL BUCKET WHEN CONTENT IS NEW
        // =========================================================

        String finalBucket =
                s3BucketProperties.finalPhotoBucket();

        boolean uploadFinalObject =
                !replaceExistingPhoto || contentChanged;

        try {

            if (uploadFinalObject) {

                s3Service.putObject(
                        finalBucket,
                        destinationKey,
                        new ByteArrayInputStream(scaledBytes),
                        scaledBytes.length,
                        "image/jpeg"
                );

                log.info(
                        "photo.upload.success bucket={} key={}",
                        finalBucket,
                        destinationKey
                );

            } else {

                log.info(
                        "photo.upload.skipped unchangedContent md5={} filename={}",
                        md5,
                        normalizedFileName
                );
            }

        } catch (Exception e) {

            log.error(
                    "photo.upload.failed md5={}",
                    md5,
                    e
            );

            throw e;
        }

        // =========================================================
        // INSERT OR UPDATE PROCESSED PHOTO ROW
        // =========================================================

        try {

            itemInventory = itemInventoryDao.upsert(itemInventory);
            itemInventoryExternalCatalogItemDao.upsert(ItemInventoryExternalCatalogItem.builder()
                    .itemInventoryId(itemInventory.getItemInventoryId())
                    .externalCatalogItemId(externalCatalogItem.getExternalCatalogItemId())
                    .primary(true)
                    .build());

            log.info(
                    "photo.db.inventory.upsert uuid={} id={}",
                    uuid,
                    itemInventory.getItemInventoryId()
            );

            if (replaceExistingPhoto) {

                updateExistingPhoto(
                        existingLogicalPhoto.orElseThrow(),
                        md5,
                        metadataHash,
                        normalizedFileName,
                        finalBucket,
                        destinationKey,
                        scaledBytes.length,
                        primaryPhoto,
                        photoCaption,
                        contentChanged
                );

            } else {

                itemInventoryPhotoDao.insertPhoto(
                        itemInventory.getItemInventoryId(),
                        md5,
                        metadataHash,
                        normalizedFileName,
                        finalBucket,
                        destinationKey,
                        scaledBytes.length,
                        primaryPhoto,
                        photoCaption,
                        PROCESSED
                );

                log.info(
                        "photo.db.photo.inserted md5={} itemInventoryId={} filename={}",
                        md5,
                        itemInventory.getItemInventoryId(),
                        normalizedFileName
                );
            }

        } catch (Exception e) {

            // =====================================================
            // CLEANUP S3 OBJECT IF DB WRITE FAILS
            // =====================================================

            if (uploadFinalObject) {

                try {

                    s3Service.deleteObject(
                            finalBucket,
                            destinationKey
                    );

                    log.warn(
                            "photo.upload.rollback.deleted bucket={} key={}",
                            finalBucket,
                            destinationKey
                    );

                } catch (Exception cleanupException) {

                    log.error(
                            "photo.upload.rollback.failed key={}",
                            destinationKey,
                            cleanupException
                    );
                }
            }

            throw e;
        }

        // =========================================================
        // PRIMARY PHOTO
        // =========================================================

        if (primaryPhoto) {

            itemInventoryPhotoDao.setPrimaryPhoto(
                    itemInventory.getItemInventoryId(),
                    md5
            );

            log.info(
                    "photo.primary.set itemInventoryId={} md5={}",
                    itemInventory.getItemInventoryId(),
                    md5
            );
        }

        // =========================================================
        // DELETE REPLACED FINAL OBJECT AFTER DB UPDATE
        // =========================================================

        if (replaceExistingPhoto && contentChanged) {

            ItemInventoryPhoto replacedPhoto =
                    existingLogicalPhoto.orElseThrow();

            try {

                s3Service.deleteObject(
                        replacedPhoto.getS3Bucket(),
                        replacedPhoto.getS3Key()
                );

                log.info(
                        "photo.replaced.source.deleted bucket={} key={}",
                        replacedPhoto.getS3Bucket(),
                        replacedPhoto.getS3Key()
                );

            } catch (Exception e) {

                log.warn(
                        "photo.replaced.source.delete.failed bucket={} key={}",
                        replacedPhoto.getS3Bucket(),
                        replacedPhoto.getS3Key(),
                        e
                );
            }
        }

        // =========================================================
        // DELETE SOURCE OBJECT
        // =========================================================

        if (deleteSource && event != null) {

            try {

                s3Service.deleteObject(
                        event.getBucket(),
                        event.getObjectKey()
                );
                deleteSourceFolderMarkers(event.getBucket(), event.getObjectKey());

                log.info(
                        "photo.source.deleted bucket={} key={}",
                        event.getBucket(),
                        event.getObjectKey()
                );

            } catch (Exception e) {

                log.warn(
                        "photo.source.delete.failed bucket={} key={}",
                        event.getBucket(),
                        event.getObjectKey(),
                        e
                );
            }
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private ItemInventory buildItemInventory(
            String uuid,
            String description,
            ImageMetadata metadata,
            Optional<ItemInventory> existingItemInventory
    ) {

        ItemInventory itemInventory = new ItemInventory();

        itemInventory.setUuid(uuid);

        itemInventory.setDescription(
                valueOrExisting(
                        metadata.hasCaption(),
                        description,
                        existingItemInventory.map(ItemInventory::getDescription)
                                .orElse(null)
                )
        );

        itemInventory.setSealed(
                valueOrExisting(
                        metadata.hasSealed(),
                        metadata.isSealed(),
                        existingItemInventory.map(ItemInventory::getSealed)
                                .orElse(false)
                )
        );

        itemInventory.setBuiltOnce(
                valueOrExisting(
                        metadata.hasBuiltOnce(),
                        metadata.isBuiltOnce(),
                        existingItemInventory.map(ItemInventory::getBuiltOnce)
                                .orElse(false)
                )
        );

        itemInventory.setActive(true);

        itemInventory.setItemConditionId(
                metadata.itemConditionOptional()
                        .map(this::conditionId)
                        .orElseGet(() ->
                                existingItemInventory.map(ItemInventory::getItemConditionId)
                                        .orElse(null)
                        )
        );

        itemInventory.setBoxConditionId(
                metadata.boxConditionOptional()
                        .map(this::conditionId)
                        .orElseGet(() ->
                                existingItemInventory.map(ItemInventory::getBoxConditionId)
                                        .orElse(null)
                        )
        );

        itemInventory.setInstructionsConditionId(
                metadata.instructionsConditionOptional()
                        .map(this::conditionId)
                        .orElseGet(() ->
                                existingItemInventory.map(ItemInventory::getInstructionsConditionId)
                                        .orElse(null)
                        )
        );

        return itemInventory;
    }

    private String buildKey(
            String bricklinkItemNumber,
            String uuid,
            String md5
    ) {

        return "%s/%s/%s.jpg".formatted(
                bricklinkItemNumber,
                uuid,
                md5
        );
    }

    private boolean isDuplicateOfDifferentLogicalPhoto(
            Optional<ItemInventoryPhoto> existingMd5Owner,
            Optional<ItemInventoryPhoto> existingLogicalPhoto
    ) {

        if (existingMd5Owner.isEmpty()) {
            return false;
        }

        if (existingLogicalPhoto.isEmpty()) {
            return true;
        }

        Integer md5OwnerId =
                existingMd5Owner.get().getItemInventoryPhotoId();

        Integer logicalPhotoId =
                existingLogicalPhoto.get().getItemInventoryPhotoId();

        return !md5OwnerId.equals(logicalPhotoId);
    }

    private void updateExistingPhoto(
            ItemInventoryPhoto existingPhoto,
            String md5,
            String metadataHash,
            String normalizedFileName,
            String finalBucket,
            String destinationKey,
            long fileSize,
            boolean primaryPhoto,
            String caption,
            boolean contentChanged
    ) {

        int updated;

        if (contentChanged) {

            updated =
                    itemInventoryPhotoDao.replaceStoredObject(
                            existingPhoto.getItemInventoryPhotoId(),
                            normalizedFileName,
                            md5,
                            metadataHash,
                            finalBucket,
                            destinationKey,
                            fileSize,
                            primaryPhoto,
                            caption,
                            PROCESSED
                    );

        } else {

            updated =
                    itemInventoryPhotoDao.updateMetadata(
                            existingPhoto.getItemInventoryPhotoId(),
                            normalizedFileName,
                            metadataHash,
                            primaryPhoto,
                            caption,
                            PROCESSED
                    );
        }

        if (updated == 0) {
            throw new IllegalStateException(
                    "Failed to update item inventory photo [%s]"
                            .formatted(existingPhoto.getItemInventoryPhotoId())
            );
        }

        log.info(
                "photo.db.photo.updated id={} md5={} contentChanged={}",
                existingPhoto.getItemInventoryPhotoId(),
                md5,
                contentChanged
        );
    }

    private String normalizeFileName(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Photo filename is missing");
        }

        String normalizedPath =
                filename.replace('\\', '/').trim();

        int lastSlash =
                normalizedPath.lastIndexOf('/');

        String normalizedFileName =
                lastSlash >= 0
                        ? normalizedPath.substring(lastSlash + 1)
                        : normalizedPath;

        if (normalizedFileName.isBlank()) {
            throw new IllegalArgumentException(
                    "Photo filename [%s] does not contain a file name"
                            .formatted(filename)
            );
        }

        return normalizedFileName;
    }

    private boolean moveSourceObject(
            PhotoUploadEvent event,
            String destinationPrefix,
            String reason
    ) {

        String bucket =
                event.getBucket();

        String sourceKey =
                event.getObjectKey();

        if (!isMovablePhotoUploadKey(sourceKey)) {
            log.info(
                    "photo.source.move.skipped reason={} bucket={} key={}",
                    reason,
                    bucket,
                    sourceKey
            );
            return false;
        }

        String destinationKey =
                destinationPrefix + sourceKey.substring(PHOTO_UPLOAD_PREFIX.length());

        try {

            s3Service.copyObject(
                    bucket,
                    sourceKey,
                    bucket,
                    destinationKey
            );

            s3Service.deleteObject(
                    bucket,
                    sourceKey
            );
            deleteSourceFolderMarkers(bucket, sourceKey);

            log.info(
                    "photo.source.moved reason={} bucket={} sourceKey={} destinationKey={}",
                    reason,
                    bucket,
                    sourceKey,
                    destinationKey
            );
            return true;

        } catch (Exception e) {

            log.warn(
                    "photo.source.move.failed reason={} bucket={} sourceKey={} destinationKey={}",
                    reason,
                    bucket,
                    sourceKey,
                    destinationKey,
                    e
            );
            return false;
        }
    }

    private void deleteSourceFolderMarkers(String bucket, String sourceKey) {
        for (String folderMarkerKey : folderMarkerKeys(sourceKey)) {
            try {
                s3Service.deleteObject(bucket, folderMarkerKey);

                log.info(
                        "photo.source.folder_marker.deleted bucket={} key={}",
                        bucket,
                        folderMarkerKey
                );
            } catch (Exception e) {
                log.debug(
                        "photo.source.folder_marker.delete.skipped bucket={} key={}",
                        bucket,
                        folderMarkerKey,
                        e
                );
            }
        }
    }

    private List<String> folderMarkerKeys(String sourceKey) {
        List<String> folderMarkerKeys = new ArrayList<>();

        if (sourceKey == null || sourceKey.isBlank()) {
            return folderMarkerKeys;
        }

        String normalizedKey =
                sourceKey.replace('\\', '/');

        int slashIndex =
                normalizedKey.lastIndexOf('/');

        while (slashIndex > PHOTO_UPLOAD_PREFIX.length() - 1) {
            String folderMarkerKey =
                    normalizedKey.substring(0, slashIndex + 1);

            if (!PHOTO_UPLOAD_PREFIX.equals(folderMarkerKey)) {
                folderMarkerKeys.add(folderMarkerKey);
            }

            slashIndex =
                    normalizedKey.lastIndexOf('/', slashIndex - 1);
        }

        return folderMarkerKeys;
    }

    private boolean isMovablePhotoUploadKey(String key) {
        return key != null
                && key.startsWith(PHOTO_UPLOAD_PREFIX)
                && !key.startsWith(REJECTED_PHOTO_PREFIX)
                && !key.startsWith(DUPLICATE_PHOTO_PREFIX);
    }

    private Integer conditionId(ConditionEnum condition) {
        if (condition == null) {
            return null;
        }

        return condition.conditionId();
    }

    private boolean effectivePrimaryPhoto(
            ImageMetadata metadata,
            Optional<ItemInventoryPhoto> existingLogicalPhoto
    ) {

        if (metadata.hasPrimary()) {
            return metadata.isPrimary();
        }

        return existingLogicalPhoto
                .map(ItemInventoryPhoto::getPrimary)
                .map(Boolean::booleanValue)
                .orElse(false);
    }

    private String effectivePhotoCaption(
            ImageMetadata metadata,
            Optional<ItemInventoryPhoto> existingLogicalPhoto
    ) {

        if (metadata.hasCaption()) {
            return metadata.caption();
        }

        return existingLogicalPhoto
                .map(ItemInventoryPhoto::getCaption)
                .orElse(null);
    }

    private boolean hasNoEffectiveMetadataChanges(
            ItemInventory existingItemInventory,
            ItemInventory itemInventory,
            ItemInventoryPhoto existingPhoto,
            boolean primaryPhoto,
            String photoCaption
    ) {

        return Objects.equals(existingItemInventory.getDescription(), itemInventory.getDescription())
                && Objects.equals(existingItemInventory.getSealed(), itemInventory.getSealed())
                && Objects.equals(existingItemInventory.getBuiltOnce(), itemInventory.getBuiltOnce())
                && Objects.equals(existingItemInventory.getItemConditionId(), itemInventory.getItemConditionId())
                && Objects.equals(existingItemInventory.getBoxConditionId(), itemInventory.getBoxConditionId())
                && Objects.equals(existingItemInventory.getInstructionsConditionId(), itemInventory.getInstructionsConditionId())
                && Objects.equals(existingPhoto.getPrimary(), primaryPhoto)
                && Objects.equals(existingPhoto.getCaption(), photoCaption);
    }

    private <T> T valueOrExisting(boolean hasMetadataValue, T metadataValue, T existingValue) {
        if (hasMetadataValue) {
            return metadataValue;
        }

        return existingValue;
    }

    private static class RejectedPhotoUploadException extends RuntimeException {

        private RejectedPhotoUploadException(String message) {
            super(message);
        }

        private RejectedPhotoUploadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
