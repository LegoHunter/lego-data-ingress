package io.legohunter.egress.imagehosting;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ExternalImageAlbumImageDao;
import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dao.ItemInventoryExternalCatalogItemDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventoryExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.enums.ExternalSyncStatus;
import io.legohunter.egress.imagehosting.description.GeneratedDescriptionComposer;
import io.legohunter.egress.imagehosting.preflight.ImageHostingPreflightIssue;
import io.legohunter.egress.imagehosting.preflight.ImageHostingPreflightResult;
import io.legohunter.egress.imagehosting.preflight.ImageHostingPreflightValidator;
import io.legohunter.egress.imagehosting.publishing.ImageHostingPublishingPolicy;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateReader;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateRequest;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateSnapshot;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumCreateRequest;
import io.legohunter.imaging.model.HostedAlbumMembershipRequest;
import io.legohunter.imaging.model.HostedAlbumMetadataUpdate;
import io.legohunter.imaging.model.HostedPhotoMetadataUpdate;
import io.legohunter.imaging.model.PhotoMetaDataV1;
import io.legohunter.imaging.model.PhotoServiceErrorType;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.model.SimplePhotoServiceRequest;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import io.legohunter.ingress.s3.api.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.legohunter.data.enums.ExternalSyncStatus.FAILED;
import static io.legohunter.data.enums.ExternalSyncStatus.PENDING;
import static io.legohunter.data.enums.ExternalSyncStatus.SYNCED;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultImageHostingSyncService implements ImageHostingSyncService {
    private static final String TEMP_FILE_SUFFIX = ".jpg";
    private static final int BRICKLINK_SERVICE_ID = 2;

    private final Optional<ImageHostingService> imageHostingService;
    private final S3Service s3Service;
    private final ItemInventoryDao itemInventoryDao;
    private final ItemInventoryPhotoDao itemInventoryPhotoDao;
    private final ExternalCatalogItemDao externalCatalogItemDao;
    private final ItemInventoryExternalCatalogItemDao itemInventoryExternalCatalogItemDao;
    private final ExternalImageDao externalImageDao;
    private final ExternalImageAlbumDao externalImageAlbumDao;
    private final ExternalImageAlbumImageDao externalImageAlbumImageDao;
    private final ImageHostingSyncProperties properties;
    private final ImageHostingSyncMetricsService metricsService;
    private final ImageHostingDesiredStateReader desiredStateReader;
    private final ImageHostingPreflightValidator preflightValidator;
    private final ImageHostingRetryTemplate retryTemplate;
    private final ImageHostingPublishingPolicy publishingPolicy;
    private final GeneratedDescriptionComposer descriptionComposer;

    @Override
    public ImageHostingSyncResult syncItemInventory(Integer itemInventoryId) {
        return sync(ImageHostingSyncRequest.builder()
                .itemInventoryId(itemInventoryId)
                .build());
    }

    @Override
    public ImageHostingSyncResult sync(ImageHostingSyncRequest request) {
        if (request == null || request.getItemInventoryId() == null) {
            throw new IllegalArgumentException("itemInventoryId is required");
        }

        ImageHostingSyncProperties.ResolvedProvider provider = properties.resolveProvider(
                request.getProvider(),
                request.getExternalServiceId()
        );
        long startedAt = System.currentTimeMillis();
        log.info(
                "image_hosting.sync.started provider={} externalServiceId={} itemInventoryId={} dryRun={} retryFailed={}",
                provider.provider(),
                provider.externalServiceId(),
                request.getItemInventoryId(),
                request.isDryRun(),
                request.isRetryFailed()
        );

        try {
            ImageHostingSyncResult result = syncInternal(request, provider);
            long elapsedMillis = System.currentTimeMillis() - startedAt;
            metricsService.recordSync(result, provider.metricsTag(), elapsedMillis);
            metricsService.recordPhotoUpload(provider.metricsTag(), "uploaded", result.getPhotosUploaded());
            metricsService.recordPhotoUpload(provider.metricsTag(), "metadata_updated", result.getPhotosMetadataUpdated());
            metricsService.recordPhotoUpload(provider.metricsTag(), "skipped", result.getPhotosSkipped());
            metricsService.recordPhotoUpload(provider.metricsTag(), "failed", result.getPhotosFailed());
            log.info(
                    "image_hosting.sync.completed provider={} externalServiceId={} itemInventoryId={} outcome={} dryRun={} retryFailed={} photosDiscovered={} photosUploaded={} photosMetadataUpdated={} photosSkipped={} photosFailed={} albumId={} albumUrl={} elapsedMillis={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    result.getItemInventoryId(),
                    result.getOutcome(),
                    result.isDryRun(),
                    result.isRetryFailed(),
                    result.getPhotosDiscovered(),
                    result.getPhotosUploaded(),
                    result.getPhotosMetadataUpdated(),
                    result.getPhotosSkipped(),
                    result.getPhotosFailed(),
                    result.getAlbumId(),
                    result.getAlbumUrl(),
                    elapsedMillis
            );
            return result;
        } catch (RuntimeException e) {
            long elapsedMillis = System.currentTimeMillis() - startedAt;
            ImageHostingSyncResult result = ImageHostingSyncResult.builder()
                    .itemInventoryId(request.getItemInventoryId())
                    .provider(provider.provider())
                    .externalServiceId(provider.externalServiceId())
                    .dryRun(request.isDryRun())
                    .retryFailed(request.isRetryFailed())
                    .outcome(ImageHostingSyncOutcome.FAILED)
                    .failureMessage(e.getMessage())
                    .build();
            metricsService.recordSync(result, provider.metricsTag(), elapsedMillis);
            log.error(
                    "image_hosting.sync.failed provider={} externalServiceId={} itemInventoryId={} dryRun={} retryFailed={} elapsedMillis={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    request.getItemInventoryId(),
                    request.isDryRun(),
                    request.isRetryFailed(),
                    elapsedMillis,
                    e
            );
            throw e;
        }
    }

    private ImageHostingSyncResult syncInternal(
            ImageHostingSyncRequest request,
            ImageHostingSyncProperties.ResolvedProvider provider
    ) {
        Integer externalServiceId = provider.externalServiceId();
        ImageHostingDesiredStateSnapshot desiredState = desiredStateReader.read(ImageHostingDesiredStateRequest.builder()
                .itemInventoryId(request.getItemInventoryId())
                .provider(provider.provider())
                .externalServiceId(externalServiceId)
                .build());
        SyncAccumulator accumulator = new SyncAccumulator(
                desiredState.getItemInventoryId(),
                provider.provider(),
                externalServiceId,
                request.isDryRun(),
                request.isRetryFailed(),
                desiredState.getPhotos().size()
        );
        ImageHostingPreflightResult preflightResult = preflightValidator.validate(desiredState, request);
        if (!preflightResult.isValid()) {
            preflightResult.issues().forEach(accumulator::recordPreflightIssue);
            log.warn(
                    "image_hosting.sync.preflight_failed provider={} externalServiceId={} itemInventoryId={} issueCount={}",
                    provider.provider(),
                    externalServiceId,
                    request.getItemInventoryId(),
                    preflightResult.issues().size()
            );
            return accumulator.toResult();
        }

        ItemInventory inventory = itemInventoryDao.findByItemInventoryId(request.getItemInventoryId())
                .orElseThrow(() -> new IllegalArgumentException("No item inventory found for id [%s]".formatted(request.getItemInventoryId())));
        List<ItemInventoryPhoto> photos = sortedPhotos(itemInventoryPhotoDao.findByItemInventoryId(inventory.getItemInventoryId()));

        if (photos.isEmpty()) {
            log.info(
                    "image_hosting.sync.no_photos provider={} itemInventoryId={} externalServiceId={}",
                    provider.provider(),
                    inventory.getItemInventoryId(),
                    externalServiceId
            );
            return accumulator.toResult();
        }

        if (request.isDryRun()) {
            log.info(
                    "image_hosting.sync.dry_run provider={} itemInventoryId={} externalServiceId={} photosDiscovered={}",
                    provider.provider(),
                    inventory.getItemInventoryId(),
                    externalServiceId,
                    photos.size()
            );
            return accumulator.toResult();
        }

        String albumTitle = albumTitle(inventory);
        ExternalImageAlbum album = externalImageAlbumDao.findOrCreateForItem(ExternalImageAlbum.builder()
                .externalServiceId(externalServiceId)
                .itemInventoryId(inventory.getItemInventoryId())
                .title(albumTitle)
                .syncStatus(PENDING)
                .build());
        boolean albumTitleChanged = !Objects.equals(album.getTitle(), albumTitle);
        album.setTitle(albumTitle);
        String albumDescription = albumDescription(provider, inventory, album, photos);

        List<SyncedPhoto> syncedPhotos = new ArrayList<>();
        for (ItemInventoryPhoto photo : photos) {
            syncPhoto(provider, photo, request.isRetryFailed(), accumulator).ifPresent(syncedPhotos::add);
        }

        if (syncedPhotos.isEmpty()) {
            markAlbumFailed(album, "No photos were available to link after upload sync");
            accumulator.setAlbum(album);
            return accumulator.toResult();
        }

        if (isBlank(album.getExternalAlbumId())) {
            createAlbum(provider, album, inventory, albumDescription, syncedPhotos, accumulator);
        } else {
            accumulator.setAlbum(album);
            AlbumOperationResult metadataResult = updateAlbumMetadataIfNeeded(
                    provider,
                    album,
                    albumTitleChanged,
                    albumTitle,
                    albumDescription,
                    accumulator
            );
            if (AlbumOperationResult.ALBUM_NOT_FOUND.equals(metadataResult)) {
                recreateAlbum(provider, album, inventory, albumDescription, syncedPhotos, accumulator, "metadata update");
            } else {
                log.info(
                        "image_hosting.album.reused provider={} externalServiceId={} itemInventoryId={} externalImageAlbumId={} albumId={} albumUrl={}",
                        provider.provider(),
                        externalServiceId,
                        inventory.getItemInventoryId(),
                        album.getExternalImageAlbumId(),
                        album.getExternalAlbumId(),
                        album.getAlbumUrl()
                );
            }
        }

        if (!isBlank(album.getExternalAlbumId())) {
            AlbumOperationResult membershipResult = updateAlbumMembership(provider, album, syncedPhotos, accumulator);
            if (AlbumOperationResult.ALBUM_NOT_FOUND.equals(membershipResult)) {
                recreateAlbum(provider, album, inventory, albumDescription, syncedPhotos, accumulator, "membership update");
                if (!isBlank(album.getExternalAlbumId())) {
                    updateAlbumMembership(provider, album, syncedPhotos, accumulator);
                }
            }
        }

        return accumulator.toResult();
    }

    private Optional<SyncedPhoto> syncPhoto(
            ImageHostingSyncProperties.ResolvedProvider provider,
            ItemInventoryPhoto photo,
            boolean retryFailed,
            SyncAccumulator accumulator
    ) {
        Integer externalServiceId = provider.externalServiceId();
        Optional<ExternalImage> existing = externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(
                externalServiceId,
                photo.getItemInventoryPhotoId()
        );

        Optional<ExternalImage> syncedExisting = existing
                .filter(externalImage -> !isBlank(externalImage.getExternalServiceImageId()));
        if (syncedExisting.isPresent()) {
            ExternalImage externalImage = syncedExisting.orElseThrow();
            if (requiresMetadataUpdate(photo, externalImage)) {
                return updateHostedPhotoMetadata(provider, photo, externalImage, accumulator);
            }

            accumulator.recordPhotoSkipped(photo);
            refreshSyncedImageState(externalServiceId, photo, externalImage);
            log.info(
                    "image_hosting.photo_upload.skipped provider={} externalServiceId={} itemInventoryPhotoId={} externalImageId={} externalServiceImageId={} reason=already_synced",
                    provider.provider(),
                    externalServiceId,
                    photo.getItemInventoryPhotoId(),
                    externalImage.getExternalImageId(),
                    externalImage.getExternalServiceImageId()
            );
            return Optional.of(new SyncedPhoto(photo, externalImage));
        }

        if (existing.map(ExternalImage::getSyncStatus).filter(FAILED::equals).isPresent() && !retryFailed) {
            String message = "Previous failed image sync exists and retryFailed=false";
            accumulator.recordPhotoFailure(photo, message);
            existing.ifPresent(externalImage -> log.info(
                    "image_hosting.photo_upload.skipped provider={} externalServiceId={} itemInventoryPhotoId={} externalImageId={} reason=previous_failure retryFailed=false",
                    provider.provider(),
                    externalServiceId,
                    photo.getItemInventoryPhotoId(),
                    externalImage.getExternalImageId()
            ));
            return Optional.empty();
        }

        try {
            PhotoMetaDataV1 photoMetaData = loadPhotoMetaData(photo);
            try {
                PhotoMetaDataV1 loadedPhotoMetaData = photoMetaData;
                log.info(
                        "image_hosting.photo_upload.started provider={} externalServiceId={} itemInventoryPhotoId={} s3Bucket={} s3Key={} retryFailed={}",
                        provider.provider(),
                        externalServiceId,
                        photo.getItemInventoryPhotoId(),
                        photo.getS3Bucket(),
                        photo.getS3Key(),
                        retryFailed
                );
                PhotoServiceResponse<String> response = retryTemplate.execute(
                        "legacyUploadPhoto",
                        () -> imageHostingService().uploadPhoto(new SimplePhotoServiceRequest<>(loadedPhotoMetaData))
                ).response();
                if (response.isError()) {
                    String message = responseMessage(response);
                    saveFailedImage(externalServiceId, photo, existing, message);
                    accumulator.recordPhotoFailure(photo, message);
                    log.warn(
                            "image_hosting.photo_upload.failed provider={} externalServiceId={} itemInventoryPhotoId={} responseCode={} message={}",
                            provider.provider(),
                            externalServiceId,
                            photo.getItemInventoryPhotoId(),
                            response.responseCode(),
                            message
                    );
                    return Optional.empty();
                }

                ExternalImage externalImage = saveSyncedImage(externalServiceId, photo, existing, response.get());
                accumulator.recordPhotoUploaded(photo);
                log.info(
                        "image_hosting.photo_upload.completed provider={} externalServiceId={} itemInventoryPhotoId={} externalImageId={} externalServiceImageId={}",
                        provider.provider(),
                        externalServiceId,
                        photo.getItemInventoryPhotoId(),
                        externalImage.getExternalImageId(),
                        externalImage.getExternalServiceImageId()
                );
                return Optional.of(new SyncedPhoto(photo, externalImage));
            } finally {
                Files.deleteIfExists(photoMetaData.getAbsolutePath());
            }
        } catch (Exception e) {
            saveFailedImage(externalServiceId, photo, existing, e.getMessage());
            accumulator.recordPhotoFailure(photo, e.getMessage());
            log.warn(
                    "image_hosting.photo_upload.failed provider={} externalServiceId={} itemInventoryPhotoId={} message={}",
                    provider.provider(),
                    externalServiceId,
                    photo.getItemInventoryPhotoId(),
                    e.getMessage(),
                    e
            );
            return Optional.empty();
        }
    }

    private Optional<SyncedPhoto> updateHostedPhotoMetadata(
            ImageHostingSyncProperties.ResolvedProvider provider,
            ItemInventoryPhoto photo,
            ExternalImage externalImage,
            SyncAccumulator accumulator
    ) {
        HostedPhotoMetadataUpdate metadataUpdate = publishingPolicy.photoMetadataUpdate(
                externalImage.getExternalServiceImageId(),
                photo
        );

        try {
            log.info(
                    "image_hosting.photo_metadata_update.started provider={} externalServiceId={} itemInventoryPhotoId={} externalImageId={} externalServiceImageId={} metadataHash={} previousMetadataHash={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    photo.getItemInventoryPhotoId(),
                    externalImage.getExternalImageId(),
                    externalImage.getExternalServiceImageId(),
                    photo.getMetadataHash(),
                    externalImage.getMetadataHashAtSync()
            );
            PhotoServiceResponse<Void> response = retryTemplate.execute(
                    "legacyUpdatePhotoMetadata",
                    () -> imageHostingService().updatePhotoMetadata(new SimplePhotoServiceRequest<>(metadataUpdate))
            ).response();
            if (response.isError()) {
                String message = responseMessage(response);
                saveFailedImage(provider.externalServiceId(), photo, Optional.of(externalImage), message);
                accumulator.recordPhotoFailure(photo, message);
                log.warn(
                        "image_hosting.photo_metadata_update.failed provider={} externalServiceId={} itemInventoryPhotoId={} externalImageId={} responseCode={} message={}",
                        provider.provider(),
                        provider.externalServiceId(),
                        photo.getItemInventoryPhotoId(),
                        externalImage.getExternalImageId(),
                        response.responseCode(),
                        message
                );
                return Optional.empty();
            }

            refreshSyncedImageState(provider.externalServiceId(), photo, externalImage);
            accumulator.recordPhotoMetadataUpdated(photo);
            log.info(
                    "image_hosting.photo_metadata_update.completed provider={} externalServiceId={} itemInventoryPhotoId={} externalImageId={} externalServiceImageId={} metadataHash={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    photo.getItemInventoryPhotoId(),
                    externalImage.getExternalImageId(),
                    externalImage.getExternalServiceImageId(),
                    photo.getMetadataHash()
            );
            return Optional.of(new SyncedPhoto(photo, externalImage));
        } catch (Exception e) {
            saveFailedImage(provider.externalServiceId(), photo, Optional.of(externalImage), e.getMessage());
            accumulator.recordPhotoFailure(photo, e.getMessage());
            log.warn(
                    "image_hosting.photo_metadata_update.failed provider={} externalServiceId={} itemInventoryPhotoId={} externalImageId={} message={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    photo.getItemInventoryPhotoId(),
                    externalImage.getExternalImageId(),
                    e.getMessage(),
                    e
            );
            return Optional.empty();
        }
    }

    private PhotoMetaDataV1 loadPhotoMetaData(ItemInventoryPhoto photo) throws IOException {
        Files.createDirectories(properties.getTempDirectory());
        Path tempFile = Files.createTempFile(properties.getTempDirectory(), "photo-%s-".formatted(photo.getItemInventoryPhotoId()), TEMP_FILE_SUFFIX);
        try {
            // lego-imaging currently uploads from files; keep this as the only temp-file adapter point.
            try (InputStream inputStream = s3Service.getObject(photo.getS3Bucket(), photo.getS3Key())) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }

        PhotoMetaDataV1 photoMetaData = new PhotoMetaDataV1(tempFile);
        photoMetaData.setMd5(photo.getMd5());
        photoMetaData.setUploadMetadata(publishingPolicy.uploadMetadata(photo));
        return photoMetaData;
    }

    private ExternalImage saveSyncedImage(
            Integer externalServiceId,
            ItemInventoryPhoto photo,
            Optional<ExternalImage> existing,
            String externalServiceImageId
    ) {
        ZonedDateTime now = ZonedDateTime.now();
        ExternalImage externalImage = existing.orElseGet(() -> ExternalImage.builder()
                .externalServiceId(externalServiceId)
                .itemInventoryPhotoId(photo.getItemInventoryPhotoId())
                .build());

        externalImage.setExternalServiceImageId(externalServiceImageId);
        externalImage.setTitle(publishingPolicy.photoTitle(photo));
        externalImage.setMd5AtUpload(photo.getMd5());
        externalImage.setMetadataHashAtSync(photo.getMetadataHash());
        externalImage.setSyncStatus(SYNCED);
        externalImage.setErrorMessage(null);
        externalImage.setUploadedAt(Optional.ofNullable(externalImage.getUploadedAt()).orElse(now));
        externalImage.setLastSyncedAt(now);
        return Optional.ofNullable(externalImageDao.upsert(externalImage)).orElse(externalImage);
    }

    private void refreshSyncedImageState(
            Integer externalServiceId,
            ItemInventoryPhoto photo,
            ExternalImage externalImage
    ) {
        boolean changed = !Objects.equals(externalImage.getMd5AtUpload(), photo.getMd5())
                || !Objects.equals(externalImage.getMetadataHashAtSync(), photo.getMetadataHash())
                || !Objects.equals(externalImage.getTitle(), publishingPolicy.photoTitle(photo))
                || !SYNCED.equals(externalImage.getSyncStatus());
        if (!changed) {
            return;
        }

        externalImage.setExternalServiceId(externalServiceId);
        externalImage.setItemInventoryPhotoId(photo.getItemInventoryPhotoId());
        externalImage.setTitle(publishingPolicy.photoTitle(photo));
        externalImage.setMd5AtUpload(photo.getMd5());
        externalImage.setMetadataHashAtSync(photo.getMetadataHash());
        externalImage.setSyncStatus(SYNCED);
        externalImage.setErrorMessage(null);
        externalImage.setLastSyncedAt(ZonedDateTime.now());
        externalImageDao.upsert(externalImage);
    }

    private void saveFailedImage(
            Integer externalServiceId,
            ItemInventoryPhoto photo,
            Optional<ExternalImage> existing,
            String errorMessage
    ) {
        ExternalImage externalImage = existing.orElseGet(() -> ExternalImage.builder()
                .externalServiceId(externalServiceId)
                .itemInventoryPhotoId(photo.getItemInventoryPhotoId())
                .build());
        externalImage.setTitle(publishingPolicy.photoTitle(photo));
        externalImage.setMd5AtUpload(photo.getMd5());
        externalImage.setSyncStatus(FAILED);
        externalImage.setErrorMessage(errorMessage);
        externalImage.setLastSyncedAt(ZonedDateTime.now());
        externalImageDao.upsert(externalImage);
    }

    private void createAlbum(
            ImageHostingSyncProperties.ResolvedProvider provider,
            ExternalImageAlbum album,
            ItemInventory inventory,
            String albumDescription,
            List<SyncedPhoto> syncedPhotos,
            SyncAccumulator accumulator
    ) {
        try {
            log.info(
                    "image_hosting.album.create.started provider={} externalServiceId={} itemInventoryId={} externalImageAlbumId={} photos={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    inventory.getItemInventoryId(),
                    album.getExternalImageAlbumId(),
                    syncedPhotos.size()
            );
            HostedAlbumCreateRequest createRequest = HostedAlbumCreateRequest.builder()
                    .title(album.getTitle())
                    .description(albumDescription)
                    .primaryPhotoId(primaryPhoto(syncedPhotos)
                            .map(syncedPhoto -> syncedPhoto.externalImage().getExternalServiceImageId())
                            .orElseThrow(() -> new IllegalArgumentException("At least one synced photo is required")))
                    .photoIds(syncedPhotos.stream()
                            .map(syncedPhoto -> syncedPhoto.externalImage().getExternalServiceImageId())
                            .toList())
                    .build();

            PhotoServiceResponse<HostedAlbum> response = retryTemplate.execute(
                    "legacyCreateAlbum",
                    () -> imageHostingService().createAlbum(new SimplePhotoServiceRequest<>(createRequest))
            ).response();
            if (response.isError()) {
                String message = responseMessage(response);
                markAlbumFailed(album, message);
                accumulator.recordAlbumFailure(album, message);
                metricsService.recordAlbumOperation(provider.metricsTag(), "create", "failed");
                log.warn(
                        "image_hosting.album.create.failed provider={} externalServiceId={} itemInventoryId={} externalImageAlbumId={} responseCode={} message={}",
                        provider.provider(),
                        provider.externalServiceId(),
                        inventory.getItemInventoryId(),
                        album.getExternalImageAlbumId(),
                        response.responseCode(),
                        message
                );
                return;
            }

            HostedAlbum hostedAlbum = response.get();
            album.setExternalAlbumId(hostedAlbum.getId());
            album.setAlbumUrl(hostedAlbum.getUrl());
            album.setSyncStatus(SYNCED);
            album.setErrorMessage(null);
            album.setLastSyncedAt(ZonedDateTime.now());
            externalImageAlbumDao.update(album);
            accumulator.albumCreated = true;
            accumulator.setAlbum(album);
            metricsService.recordAlbumOperation(provider.metricsTag(), "create", "success");
            log.info(
                    "image_hosting.album.create.completed provider={} externalServiceId={} itemInventoryId={} externalImageAlbumId={} albumId={} albumUrl={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    inventory.getItemInventoryId(),
                    album.getExternalImageAlbumId(),
                    album.getExternalAlbumId(),
                    album.getAlbumUrl()
            );
        } catch (Exception e) {
            markAlbumFailed(album, e.getMessage());
            accumulator.recordAlbumFailure(album, e.getMessage());
            metricsService.recordAlbumOperation(provider.metricsTag(), "create", "failed");
            log.warn(
                    "image_hosting.album.create.failed provider={} externalServiceId={} itemInventoryId={} externalImageAlbumId={} message={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    inventory.getItemInventoryId(),
                    album.getExternalImageAlbumId(),
                    e.getMessage(),
                    e
            );
        }
    }

    private AlbumOperationResult updateAlbumMembership(
            ImageHostingSyncProperties.ResolvedProvider provider,
            ExternalImageAlbum album,
            List<SyncedPhoto> syncedPhotos,
            SyncAccumulator accumulator
    ) {
        Set<String> photoIds = syncedPhotos.stream()
                .map(SyncedPhoto::externalImage)
                .map(ExternalImage::getExternalServiceImageId)
                .filter(id -> !isBlank(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String primaryPhotoId = primaryPhoto(syncedPhotos)
                .map(SyncedPhoto::externalImage)
                .map(ExternalImage::getExternalServiceImageId)
                .orElseGet(() -> photoIds.stream().findFirst().orElseThrow());

        try {
            log.info(
                    "image_hosting.album_membership.update.started provider={} externalServiceId={} externalImageAlbumId={} albumId={} photoCount={} primaryPhotoId={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    album.getExternalImageAlbumId(),
                    album.getExternalAlbumId(),
                    photoIds.size(),
                    primaryPhotoId
            );
            HostedAlbumMembershipRequest membershipRequest = HostedAlbumMembershipRequest.builder()
                    .albumId(album.getExternalAlbumId())
                    .primaryPhotoId(primaryPhotoId)
                    .photoIds(photoIds)
                    .build();
            PhotoServiceResponse<Void> response = retryTemplate.execute(
                    "legacyUpdateAlbumMembership",
                    () -> imageHostingService().updateAlbumMembership(new SimplePhotoServiceRequest<>(membershipRequest))
            ).response();
            if (response.isError()) {
                String message = responseMessage(response);
                if (isAlbumNotFound(response)) {
                    metricsService.recordAlbumOperation(provider.metricsTag(), "membership", "album_not_found");
                    log.warn(
                            "image_hosting.album_membership.update.remote_missing provider={} externalServiceId={} externalImageAlbumId={} albumId={} responseCode={} message={}",
                            provider.provider(),
                            provider.externalServiceId(),
                            album.getExternalImageAlbumId(),
                            album.getExternalAlbumId(),
                            response.responseCode(),
                            message
                    );
                    return AlbumOperationResult.ALBUM_NOT_FOUND;
                }
                markAlbumFailed(album, message);
                accumulator.recordAlbumFailure(album, message);
                metricsService.recordAlbumOperation(provider.metricsTag(), "membership", "failed");
                log.warn(
                        "image_hosting.album_membership.update.failed provider={} externalServiceId={} externalImageAlbumId={} albumId={} responseCode={} message={}",
                        provider.provider(),
                        provider.externalServiceId(),
                        album.getExternalImageAlbumId(),
                        album.getExternalAlbumId(),
                        response.responseCode(),
                        message
                );
                return AlbumOperationResult.FAILED;
            }

            persistAlbumMembership(album, syncedPhotos, primaryPhotoId, accumulator);
            album.setSyncStatus(SYNCED);
            album.setErrorMessage(null);
            album.setLastSyncedAt(ZonedDateTime.now());
            externalImageAlbumDao.update(album);
            accumulator.membershipUpdated = true;
            accumulator.setAlbum(album);
            metricsService.recordAlbumOperation(provider.metricsTag(), "membership", "success");
            log.info(
                    "image_hosting.album_membership.update.completed provider={} externalServiceId={} externalImageAlbumId={} albumId={} photoCount={} primaryPhotoId={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    album.getExternalImageAlbumId(),
                    album.getExternalAlbumId(),
                    photoIds.size(),
                    primaryPhotoId
            );
            return AlbumOperationResult.SUCCESS;
        } catch (Exception e) {
            markAlbumFailed(album, e.getMessage());
            accumulator.recordAlbumFailure(album, e.getMessage());
            metricsService.recordAlbumOperation(provider.metricsTag(), "membership", "failed");
            log.warn(
                    "image_hosting.album_membership.update.failed provider={} externalServiceId={} externalImageAlbumId={} albumId={} message={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    album.getExternalImageAlbumId(),
                    album.getExternalAlbumId(),
                    e.getMessage(),
                    e
            );
            return AlbumOperationResult.FAILED;
        }
    }

    private AlbumOperationResult updateAlbumMetadataIfNeeded(
            ImageHostingSyncProperties.ResolvedProvider provider,
            ExternalImageAlbum album,
            boolean albumTitleChanged,
            String desiredTitle,
            String desiredDescription,
            SyncAccumulator accumulator
    ) {
        if (isBlank(album.getExternalAlbumId()) || !albumTitleChanged) {
            return AlbumOperationResult.SKIPPED;
        }

        try {
            log.info(
                    "image_hosting.album_metadata.update.started provider={} externalServiceId={} externalImageAlbumId={} albumId={} title={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    album.getExternalImageAlbumId(),
                    album.getExternalAlbumId(),
                    desiredTitle
            );
            PhotoServiceResponse<Void> response = retryTemplate.execute(
                    "legacyUpdateAlbumMetadata",
                    () -> imageHostingService().updateAlbumMetadata(new SimplePhotoServiceRequest<>(
                            HostedAlbumMetadataUpdate.builder()
                                    .albumId(album.getExternalAlbumId())
                                    .title(desiredTitle)
                                    .description(desiredDescription)
                                    .build()
                    ))
            ).response();
            if (response.isError()) {
                String message = responseMessage(response);
                if (isAlbumNotFound(response)) {
                    metricsService.recordAlbumOperation(provider.metricsTag(), "metadata", "album_not_found");
                    log.warn(
                            "image_hosting.album_metadata.update.remote_missing provider={} externalServiceId={} externalImageAlbumId={} albumId={} responseCode={} message={}",
                            provider.provider(),
                            provider.externalServiceId(),
                            album.getExternalImageAlbumId(),
                            album.getExternalAlbumId(),
                            response.responseCode(),
                            message
                    );
                    return AlbumOperationResult.ALBUM_NOT_FOUND;
                }
                markAlbumFailed(album, message);
                accumulator.recordAlbumFailure(album, message);
                metricsService.recordAlbumOperation(provider.metricsTag(), "metadata", "failed");
                log.warn(
                        "image_hosting.album_metadata.update.failed provider={} externalServiceId={} externalImageAlbumId={} albumId={} responseCode={} message={}",
                        provider.provider(),
                        provider.externalServiceId(),
                        album.getExternalImageAlbumId(),
                        album.getExternalAlbumId(),
                        response.responseCode(),
                        message
                );
                return AlbumOperationResult.FAILED;
            }

            album.setTitle(desiredTitle);
            album.setErrorMessage(null);
            album.setLastSyncedAt(ZonedDateTime.now());
            externalImageAlbumDao.update(album);
            accumulator.setAlbum(album);
            metricsService.recordAlbumOperation(provider.metricsTag(), "metadata", "success");
            log.info(
                    "image_hosting.album_metadata.update.completed provider={} externalServiceId={} externalImageAlbumId={} albumId={} title={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    album.getExternalImageAlbumId(),
                    album.getExternalAlbumId(),
                    desiredTitle
            );
            return AlbumOperationResult.SUCCESS;
        } catch (Exception e) {
            markAlbumFailed(album, e.getMessage());
            accumulator.recordAlbumFailure(album, e.getMessage());
            metricsService.recordAlbumOperation(provider.metricsTag(), "metadata", "failed");
            log.warn(
                    "image_hosting.album_metadata.update.failed provider={} externalServiceId={} externalImageAlbumId={} albumId={} message={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    album.getExternalImageAlbumId(),
                    album.getExternalAlbumId(),
                    e.getMessage(),
                    e
            );
            return AlbumOperationResult.FAILED;
        }
    }

    private void recreateAlbum(
            ImageHostingSyncProperties.ResolvedProvider provider,
            ExternalImageAlbum album,
            ItemInventory inventory,
            String albumDescription,
            List<SyncedPhoto> syncedPhotos,
            SyncAccumulator accumulator,
            String reason
    ) {
        clearStaleAlbumReference(provider, album, reason);
        createAlbum(provider, album, inventory, albumDescription, syncedPhotos, accumulator);
    }

    private void clearStaleAlbumReference(
            ImageHostingSyncProperties.ResolvedProvider provider,
            ExternalImageAlbum album,
            String reason
    ) {
        String staleAlbumId = album.getExternalAlbumId();
        album.setExternalAlbumId(null);
        album.setAlbumUrl(null);
        album.setSyncStatus(PENDING);
        album.setErrorMessage(null);
        album.setLastSyncedAt(ZonedDateTime.now());
        externalImageAlbumDao.update(album);
        log.warn(
                "image_hosting.album.remote_missing provider={} externalServiceId={} externalImageAlbumId={} staleAlbumId={} reason={}",
                provider.provider(),
                provider.externalServiceId(),
                album.getExternalImageAlbumId(),
                staleAlbumId,
                reason
        );
    }

    private void persistAlbumMembership(
            ExternalImageAlbum album,
            List<SyncedPhoto> syncedPhotos,
            String primaryPhotoId,
            SyncAccumulator accumulator
    ) {
        for (int i = 0; i < syncedPhotos.size(); i++) {
            SyncedPhoto syncedPhoto = syncedPhotos.get(i);
            Long externalImageId = syncedPhoto.externalImage().getExternalImageId();
            if (externalImageId == null) {
                accumulator.recordPhotoFailure(
                        syncedPhoto.photo(),
                        "Cannot persist album membership because externalImageId is missing"
                );
                continue;
            }

            externalImageAlbumImageDao.upsert(ExternalImageAlbumImage.builder()
                    .externalImageAlbumId(album.getExternalImageAlbumId())
                    .externalImageId(externalImageId)
                    .sortOrder(i + 1)
                    .primary(primaryPhotoId.equals(syncedPhoto.externalImage().getExternalServiceImageId()))
                    .build());
        }
    }

    private void markAlbumFailed(ExternalImageAlbum album, String message) {
        album.setSyncStatus(FAILED);
        album.setErrorMessage(message);
        album.setLastSyncedAt(ZonedDateTime.now());
        externalImageAlbumDao.update(album);
    }

    private Optional<SyncedPhoto> primaryPhoto(List<SyncedPhoto> syncedPhotos) {
        return syncedPhotos.stream()
                .filter(syncedPhoto -> Boolean.TRUE.equals(syncedPhoto.photo().getPrimary()))
                .findFirst()
                .or(() -> syncedPhotos.stream().findFirst());
    }

    private List<ItemInventoryPhoto> sortedPhotos(Set<ItemInventoryPhoto> photos) {
        return photos.stream()
                .sorted(Comparator.comparing(ItemInventoryPhoto::getItemInventoryPhotoId, Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    private String albumTitle(ItemInventory inventory) {
        Optional<ExternalCatalogItem> externalCatalogItem = bricklinkExternalCatalogItem(inventory);
        if (externalCatalogItem.isPresent()) {
            ExternalCatalogItem item = externalCatalogItem.get();
            return "%s - %s".formatted(item.getExternalItemKey(), item.getItemName());
        }
        if (!isBlank(inventory.getDescription())) {
            return inventory.getDescription();
        }
        return "Inventory %s".formatted(inventory.getUuid());
    }

    private String albumDescription(
            ImageHostingSyncProperties.ResolvedProvider provider,
            ItemInventory inventory,
            ExternalImageAlbum album,
            List<ItemInventoryPhoto> photos
    ) {
        return descriptionComposer.compose(
                provider,
                inventory,
                bricklinkExternalCatalogItem(inventory).orElse(null),
                album,
                photos
        ).getDescription();
    }

    private Optional<ExternalCatalogItem> bricklinkExternalCatalogItem(ItemInventory inventory) {
        return itemInventoryExternalCatalogItemDao.findByItemInventoryId(inventory.getItemInventoryId()).stream()
                .map(ItemInventoryExternalCatalogItem::getExternalCatalogItemId)
                .map(externalCatalogItemDao::findByExternalCatalogItemId)
                .flatMap(Optional::stream)
                .filter(item -> BRICKLINK_SERVICE_ID == item.getExternalServiceId())
                .filter(item -> !isBlank(item.getExternalItemKey()))
                .filter(item -> !isBlank(item.getItemName()))
                .findFirst();
    }

    private boolean requiresMetadataUpdate(ItemInventoryPhoto photo, ExternalImage externalImage) {
        return !isBlank(photo.getMetadataHash())
                && !Objects.equals(photo.getMetadataHash(), externalImage.getMetadataHashAtSync());
    }

    private String responseMessage(PhotoServiceResponse<?> response) {
        if (!isBlank(response.responseMessage())) {
            return response.responseMessage();
        }
        return "Image hosting provider returned response code [%s]".formatted(response.responseCode());
    }

    private boolean isAlbumNotFound(PhotoServiceResponse<?> response) {
        return PhotoServiceErrorType.ALBUM_NOT_FOUND.equals(response.errorType());
    }

    private ImageHostingService imageHostingService() {
        return imageHostingService.orElseThrow(() -> new IllegalStateException("No ImageHostingService bean is configured"));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SyncedPhoto(ItemInventoryPhoto photo, ExternalImage externalImage) {
    }

    private enum AlbumOperationResult {
        SUCCESS,
        FAILED,
        ALBUM_NOT_FOUND,
        SKIPPED
    }

    private static class SyncAccumulator {
        private final Integer itemInventoryId;
        private final String provider;
        private final Integer externalServiceId;
        private final boolean dryRun;
        private final boolean retryFailed;
        private final int photosDiscovered;
        private final List<String> failureMessages = new ArrayList<>();
        private final List<Integer> uploadedPhotoIds = new ArrayList<>();
        private final List<Integer> metadataUpdatedPhotoIds = new ArrayList<>();
        private final List<Integer> skippedPhotoIds = new ArrayList<>();
        private final List<Integer> failedPhotoIds = new ArrayList<>();
        private int photosUploaded;
        private int photosMetadataUpdated;
        private int photosSkipped;
        private int photosFailed;
        private Long externalImageAlbumId;
        private String albumId;
        private String albumUrl;
        private boolean albumCreated;
        private boolean membershipUpdated;

        private SyncAccumulator(
                Integer itemInventoryId,
                String provider,
                Integer externalServiceId,
                boolean dryRun,
                boolean retryFailed,
                int photosDiscovered
        ) {
            this.itemInventoryId = itemInventoryId;
            this.provider = provider;
            this.externalServiceId = externalServiceId;
            this.dryRun = dryRun;
            this.retryFailed = retryFailed;
            this.photosDiscovered = photosDiscovered;
        }

        private void recordPhotoUploaded(ItemInventoryPhoto photo) {
            photosUploaded++;
            uploadedPhotoIds.add(photo.getItemInventoryPhotoId());
        }

        private void recordPhotoMetadataUpdated(ItemInventoryPhoto photo) {
            photosMetadataUpdated++;
            metadataUpdatedPhotoIds.add(photo.getItemInventoryPhotoId());
        }

        private void recordPhotoSkipped(ItemInventoryPhoto photo) {
            photosSkipped++;
            skippedPhotoIds.add(photo.getItemInventoryPhotoId());
        }

        private void recordPhotoFailure(ItemInventoryPhoto photo, String message) {
            photosFailed++;
            failedPhotoIds.add(photo.getItemInventoryPhotoId());
            failureMessages.add("Photo [%s] failed: %s".formatted(photo.getItemInventoryPhotoId(), message));
        }

        private void recordAlbumFailure(ExternalImageAlbum album, String message) {
            setAlbum(album);
            failureMessages.add(message);
        }

        private void recordPreflightIssue(ImageHostingPreflightIssue issue) {
            String message = "Preflight failed: %s".formatted(issue.getMessage());
            if (issue.getItemInventoryPhotoId() == null) {
                failureMessages.add(message);
                return;
            }

            photosFailed++;
            failedPhotoIds.add(issue.getItemInventoryPhotoId());
            failureMessages.add("Photo [%s] failed: %s".formatted(issue.getItemInventoryPhotoId(), message));
        }

        private void setAlbum(ExternalImageAlbum album) {
            externalImageAlbumId = album.getExternalImageAlbumId();
            albumId = album.getExternalAlbumId();
            albumUrl = album.getAlbumUrl();
        }

        private ImageHostingSyncResult toResult() {
            return ImageHostingSyncResult.builder()
                    .itemInventoryId(itemInventoryId)
                    .provider(provider)
                    .externalServiceId(externalServiceId)
                    .dryRun(dryRun)
                    .retryFailed(retryFailed)
                    .outcome(outcome())
                    .photosDiscovered(photosDiscovered)
                    .photosUploaded(photosUploaded)
                    .photosMetadataUpdated(photosMetadataUpdated)
                    .photosSkipped(photosSkipped)
                    .photosFailed(photosFailed)
                    .externalImageAlbumId(externalImageAlbumId)
                    .albumId(albumId)
                    .albumUrl(albumUrl)
                    .albumCreated(albumCreated)
                    .membershipUpdated(membershipUpdated)
                    .uploadedPhotoIds(uploadedPhotoIds)
                    .metadataUpdatedPhotoIds(metadataUpdatedPhotoIds)
                    .skippedPhotoIds(skippedPhotoIds)
                    .failedPhotoIds(failedPhotoIds)
                    .failureMessages(failureMessages)
                    .build();
        }

        private ImageHostingSyncOutcome outcome() {
            if (dryRun && failureMessages.isEmpty()) {
                return ImageHostingSyncOutcome.DRY_RUN;
            }
            if (failureMessages.isEmpty()) {
                return ImageHostingSyncOutcome.SUCCESS;
            }
            if (photosUploaded + photosSkipped > 0) {
                return ImageHostingSyncOutcome.PARTIAL_FAILURE;
            }
            return ImageHostingSyncOutcome.FAILED;
        }
    }
}
