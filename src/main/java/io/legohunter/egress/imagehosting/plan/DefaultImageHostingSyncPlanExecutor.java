package io.legohunter.egress.imagehosting.plan;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ExternalImageAlbumImageDao;
import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.egress.imagehosting.ImageHostingRetryTemplate;
import io.legohunter.egress.imagehosting.ImageHostingSyncMetricsService;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.egress.imagehosting.publishing.ImageHostingPublishingPolicy;
import io.legohunter.egress.imagehosting.shorturl.ImageHostingShortUrlResult;
import io.legohunter.egress.imagehosting.shorturl.ImageHostingShortUrlService;
import io.legohunter.egress.imagehosting.shorturl.ImageHostingShortUrlStatus;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumCreateRequest;
import io.legohunter.imaging.model.HostedAlbumMembershipRequest;
import io.legohunter.imaging.model.HostedAlbumMetadataUpdate;
import io.legohunter.imaging.model.HostedPhotoMetadataUpdate;
import io.legohunter.imaging.model.PhotoMetaDataV1;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.model.SimplePhotoServiceRequest;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import io.legohunter.imaging.service.sync.model.SyncAction;
import io.legohunter.imaging.service.sync.model.SyncActionResult;
import io.legohunter.imaging.service.sync.model.SyncActionStatus;
import io.legohunter.imaging.service.sync.model.SyncActionType;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.imaging.service.sync.model.SyncPlanMode;
import io.legohunter.imaging.service.sync.model.SyncReport;
import io.legohunter.ingress.s3.api.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.legohunter.data.enums.ExternalSyncStatus.FAILED;
import static io.legohunter.data.enums.ExternalSyncStatus.PENDING;
import static io.legohunter.data.enums.ExternalSyncStatus.SYNCED;

@Service
@RequiredArgsConstructor
public class DefaultImageHostingSyncPlanExecutor implements ImageHostingSyncPlanExecutor {
    private static final String TEMP_FILE_SUFFIX = ".jpg";

    private final Optional<ImageHostingService> imageHostingService;
    private final S3Service s3Service;
    private final ItemInventoryDao itemInventoryDao;
    private final ItemInventoryPhotoDao itemInventoryPhotoDao;
    private final ExternalImageDao externalImageDao;
    private final ExternalImageAlbumDao externalImageAlbumDao;
    private final ExternalImageAlbumImageDao externalImageAlbumImageDao;
    private final ImageHostingSyncProperties properties;
    private final ImageHostingRetryTemplate retryTemplate;
    private final ImageHostingPublishingPolicy publishingPolicy;
    private final ImageHostingShortUrlService shortUrlService;
    private final ImageHostingSyncMetricsService metricsService;

    @Override
    public SyncReport execute(SyncPlan plan, boolean allowReviewRequired) {
        if (plan == null) {
            throw new IllegalArgumentException("plan is required");
        }

        LocalDateTime startedAt = LocalDateTime.now();
        List<SyncActionResult> results = new ArrayList<>();
        for (SyncAction action : plan.getActions()) {
            results.add(executeAction(action, allowReviewRequired));
        }

        return SyncReport.builder()
                .reportId("image-hosting-report-%s".formatted(UUID.randomUUID()))
                .planId(plan.getPlanId())
                .mode(SyncPlanMode.APPLY)
                .startedAt(startedAt)
                .finishedAt(LocalDateTime.now())
                .results(results)
                .build();
    }

    private SyncActionResult executeAction(SyncAction action, boolean allowReviewRequired) {
        LocalDateTime startedAt = LocalDateTime.now();
        if (action == null) {
            return result(null, SyncActionStatus.FAILED, "Action is required", startedAt, null, null);
        }
        if (action.isBlocked()) {
            return result(action, SyncActionStatus.BLOCKED, "Action is blocked by the sync plan", startedAt, null, null);
        }
        if (action.requiresReview() && !allowReviewRequired) {
            return result(action, SyncActionStatus.BLOCKED, "Action requires review before apply", startedAt, null, null);
        }

        try {
            return switch (action.getType()) {
                case UPLOAD_PHOTO -> uploadPhoto(action, startedAt);
                case CREATE_ALBUM -> createAlbum(action, startedAt);
                case UPDATE_PHOTO_METADATA -> updatePhotoMetadata(action, startedAt);
                case UPDATE_ALBUM_METADATA -> updateAlbumMetadata(action, startedAt);
                case UPDATE_ALBUM_MEMBERSHIP, FIX_PRIMARY_PHOTO -> updateAlbumMembership(action, startedAt);
                case REPAIR_ALBUM_ID -> repairAlbumId(action, startedAt);
                case REPAIR_PHOTO_ID -> repairPhotoId(action, startedAt);
                default -> result(action, SyncActionStatus.SKIPPED, "Action type [%s] is not supported by the apply executor".formatted(action.getType()), startedAt, null, null);
            };
        } catch (RuntimeException e) {
            return result(action, SyncActionStatus.FAILED, e.getMessage(), startedAt, null, null);
        }
    }

    private SyncActionResult uploadPhoto(SyncAction action, LocalDateTime startedAt) {
        Integer itemInventoryPhotoId = requiredInteger(action, "itemInventoryPhotoId");
        Integer externalServiceId = requiredInteger(action, "externalServiceId");
        ItemInventoryPhoto photo = itemInventoryPhotoDao.findByItemInventoryPhotoId(itemInventoryPhotoId)
                .orElseThrow(() -> new IllegalArgumentException("No item inventory photo found for id [%s]".formatted(itemInventoryPhotoId)));
        Optional<ExternalImage> existing = externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(
                externalServiceId,
                itemInventoryPhotoId
        );

        PhotoMetaDataV1 photoMetaData = null;
        try {
            photoMetaData = loadPhotoMetaData(photo);
            PhotoMetaDataV1 loadedPhotoMetaData = photoMetaData;
            ImageHostingRetryTemplate.AttemptedResponse<String> attemptedResponse = retryTemplate.execute(
                    "uploadPhoto",
                    () -> imageHostingService().uploadPhoto(new SimplePhotoServiceRequest<>(loadedPhotoMetaData))
            );
            PhotoServiceResponse<String> response = attemptedResponse.response();
            if (response.isError()) {
                saveFailedImage(externalServiceId, photo, existing, responseMessage(response));
                return responseResult(action, SyncActionStatus.FAILED, attemptedResponse, responseMessage(response), startedAt, null);
            }

            ExternalImage externalImage = saveSyncedImage(externalServiceId, photo, existing, response.get());
            return result(
                    action,
                    SyncActionStatus.SUCCEEDED,
                    "Uploaded photo to Flickr",
                    startedAt,
                    null,
                    externalImage.getExternalServiceImageId(),
                    attemptedResponse
            );
        } catch (IOException e) {
            saveFailedImage(externalServiceId, photo, existing, e.getMessage());
            return result(action, SyncActionStatus.FAILED, e.getMessage(), startedAt, null, null);
        } finally {
            deleteTempFile(photoMetaData);
        }
    }

    private SyncActionResult createAlbum(SyncAction action, LocalDateTime startedAt) {
        Integer itemInventoryId = requiredInteger(action, "itemInventoryId");
        Integer externalServiceId = requiredInteger(action, "externalServiceId");
        ItemInventory inventory = itemInventoryDao.findByItemInventoryId(itemInventoryId)
                .orElseThrow(() -> new IllegalArgumentException("No item inventory found for id [%s]".formatted(itemInventoryId)));
        ExternalImageAlbum album = findOrCreateAlbum(action, externalServiceId, itemInventoryId);
        List<SyncedPhoto> syncedPhotos = syncedPhotos(externalServiceId, itemInventoryId);
        if (syncedPhotos.isEmpty()) {
            markAlbumFailed(album, "Cannot create Flickr album without synced photo ids");
            return result(action, SyncActionStatus.FAILED, "Cannot create Flickr album without synced photo ids", startedAt, null, null);
        }

        HostedAlbumCreateRequest createRequest = HostedAlbumCreateRequest.builder()
                .title(attribute(action, "desiredTitle").orElse(album.getTitle()))
                .description(attribute(action, "desiredDescription").orElse("Inventory item [%s]".formatted(inventory.getUuid())))
                .primaryPhotoId(primaryPhotoId(syncedPhotos))
                .photoIds(syncedPhotos.stream()
                        .map(syncedPhoto -> syncedPhoto.externalImage().getExternalServiceImageId())
                        .toList())
                .build();

        ImageHostingRetryTemplate.AttemptedResponse<HostedAlbum> attemptedResponse = retryTemplate.execute(
                "createAlbum",
                () -> imageHostingService().createAlbum(new SimplePhotoServiceRequest<>(createRequest))
        );
        PhotoServiceResponse<HostedAlbum> response = attemptedResponse.response();
        if (response.isError()) {
            markAlbumFailed(album, responseMessage(response));
            return responseResult(action, SyncActionStatus.FAILED, attemptedResponse, responseMessage(response), startedAt, null);
        }

        HostedAlbum hostedAlbum = response.get();
        album.setExternalAlbumId(hostedAlbum.getId());
        album.setAlbumUrl(hostedAlbum.getUrl());
        ImageHostingShortUrlResult shortUrlResult = shortUrlService.generateShortUrl(hostedAlbum.getUrl());
        recordShortUrlResult(action, "generate", shortUrlResult);
        if (hasShortUrl(shortUrlResult)) {
            album.setShortUrl(shortUrlResult.getShortUrl());
        }
        album.setSyncStatus(SYNCED);
        album.setErrorMessage(null);
        album.setLastSyncedAt(ZonedDateTime.now());
        externalImageAlbumDao.update(album);
        persistAlbumMembership(album, syncedPhotos);
        metricsService.recordAlbumCreation(providerTag(action), "created");
        return result(
                action,
                SyncActionStatus.SUCCEEDED,
                "Created Flickr album; %s".formatted(shortUrlMessage(shortUrlResult)),
                startedAt,
                hostedAlbum.getId(),
                null,
                attemptedResponse
        );
    }

    private SyncActionResult updatePhotoMetadata(SyncAction action, LocalDateTime startedAt) {
        String photoId = requiredPhotoId(action);
        ImageHostingRetryTemplate.AttemptedResponse<Void> attemptedResponse = retryTemplate.execute(
                "updatePhotoMetadata",
                () -> imageHostingService().updatePhotoMetadata(new SimplePhotoServiceRequest<>(
                        HostedPhotoMetadataUpdate.builder()
                                .photoId(photoId)
                                .title(attribute(action, "desiredTitle").orElse(""))
                                .description(attribute(action, "desiredDescription").orElse(""))
                                .build()
                ))
        );
        PhotoServiceResponse<Void> response = attemptedResponse.response();
        if (response.isError()) {
            markImageFailed(action, responseMessage(response));
            return responseResult(action, SyncActionStatus.FAILED, attemptedResponse, responseMessage(response), startedAt, null);
        }

        updateExternalImageMetadata(action);
        return result(action, SyncActionStatus.SUCCEEDED, "Updated Flickr photo metadata", startedAt, null, photoId, attemptedResponse);
    }

    private SyncActionResult updateAlbumMetadata(SyncAction action, LocalDateTime startedAt) {
        String albumId = requiredAlbumId(action);
        ImageHostingRetryTemplate.AttemptedResponse<Void> attemptedResponse = retryTemplate.execute(
                "updateAlbumMetadata",
                () -> imageHostingService().updateAlbumMetadata(new SimplePhotoServiceRequest<>(
                        HostedAlbumMetadataUpdate.builder()
                                .albumId(albumId)
                                .title(attribute(action, "desiredTitle").orElse(""))
                                .description(attribute(action, "desiredDescription").orElse(""))
                                .build()
                ))
        );
        PhotoServiceResponse<Void> response = attemptedResponse.response();
        if (response.isError()) {
            markAlbumFailed(action, responseMessage(response));
            return responseResult(action, SyncActionStatus.FAILED, attemptedResponse, responseMessage(response), startedAt, albumId);
        }

        updateExternalAlbumMetadata(action);
        return result(action, SyncActionStatus.SUCCEEDED, "Updated Flickr album metadata", startedAt, albumId, null, attemptedResponse);
    }

    private SyncActionResult updateAlbumMembership(SyncAction action, LocalDateTime startedAt) {
        Set<String> remotePhotoIds = csvAttribute(action, "remotePhotoIds");
        if (!remotePhotoIds.isEmpty() && csvAttribute(action, "desiredPhotoIds").isEmpty()) {
            return repairAlbumMembership(action, remotePhotoIds, startedAt);
        }

        String albumId = requiredAlbumId(action);
        Set<String> photoIds = csvAttribute(action, "desiredPhotoIds");
        if (photoIds.isEmpty()) {
            return result(action, SyncActionStatus.FAILED, "desiredPhotoIds is required", startedAt, albumId, null);
        }
        String primaryPhotoId = attribute(action, "desiredPrimaryPhotoId")
                .filter(this::hasText)
                .orElseGet(() -> photoIds.stream().findFirst().orElseThrow());

        ImageHostingRetryTemplate.AttemptedResponse<Void> attemptedResponse = retryTemplate.execute(
                "updateAlbumMembership",
                () -> imageHostingService().updateAlbumMembership(new SimplePhotoServiceRequest<>(
                        HostedAlbumMembershipRequest.builder()
                                .albumId(albumId)
                                .primaryPhotoId(primaryPhotoId)
                                .photoIds(photoIds)
                                .build()
                ))
        );
        PhotoServiceResponse<Void> response = attemptedResponse.response();
        if (response.isError()) {
            markAlbumFailed(action, responseMessage(response));
            return responseResult(action, SyncActionStatus.FAILED, attemptedResponse, responseMessage(response), startedAt, albumId);
        }

        persistAlbumMembership(action, photoIds, primaryPhotoId);
        return result(action, SyncActionStatus.SUCCEEDED, "Updated Flickr album membership", startedAt, albumId, primaryPhotoId, attemptedResponse);
    }

    private SyncActionResult repairAlbumId(SyncAction action, LocalDateTime startedAt) {
        Optional<String> remoteAlbumId = attribute(action, "remoteAlbumId").filter(this::hasText);
        if (remoteAlbumId.isPresent()) {
            return adoptRemoteAlbum(action, remoteAlbumId.get(), startedAt);
        }

        ExternalImageAlbum album = findAlbum(action);
        album.setExternalAlbumId(null);
        album.setAlbumUrl(null);
        album.setSyncStatus(PENDING);
        album.setErrorMessage(null);
        album.setLastSyncedAt(ZonedDateTime.now());
        externalImageAlbumDao.update(album);
        return result(action, SyncActionStatus.SUCCEEDED, "Cleared stale Flickr album id from DB", startedAt, null, null);
    }

    private SyncActionResult repairPhotoId(SyncAction action, LocalDateTime startedAt) {
        Optional<String> remotePhotoId = attribute(action, "remotePhotoId").filter(this::hasText);
        if (remotePhotoId.isPresent()) {
            return adoptRemotePhoto(action, remotePhotoId.get(), startedAt);
        }

        ExternalImage image = findImage(action);
        image.setExternalServiceImageId(null);
        image.setSyncStatus(PENDING);
        image.setErrorMessage(null);
        image.setLastSyncedAt(ZonedDateTime.now());
        externalImageDao.update(image);
        return result(action, SyncActionStatus.SUCCEEDED, "Cleared stale Flickr photo id from DB", startedAt, null, null);
    }

    private SyncActionResult adoptRemoteAlbum(SyncAction action, String remoteAlbumId, LocalDateTime startedAt) {
        Integer externalServiceId = requiredInteger(action, "externalServiceId");
        Integer itemInventoryId = requiredInteger(action, "itemInventoryId");
        Optional<ExternalImageAlbum> existingRemoteOwner =
                externalImageAlbumDao.findByExternalServiceIdAndExternalAlbumId(externalServiceId, remoteAlbumId);
        if (existingRemoteOwner.isPresent()
                && !itemInventoryId.equals(existingRemoteOwner.get().getItemInventoryId())) {
            metricsService.recordAlbumAdoption(providerTag(action), "blocked");
            return result(
                    action,
                    SyncActionStatus.FAILED,
                    "Remote album id [%s] is already linked to item inventory [%s]".formatted(
                            remoteAlbumId,
                            existingRemoteOwner.get().getItemInventoryId()
                    ),
                    startedAt,
                    remoteAlbumId,
                    null
            );
        }

        ExternalImageAlbum album = findOrCreateAlbum(action, externalServiceId, itemInventoryId);
        album.setExternalAlbumId(remoteAlbumId);
        attribute(action, "remoteAlbumUrl").filter(this::hasText).ifPresent(album::setAlbumUrl);
        ImageHostingShortUrlResult shortUrlResult = shortUrlService.recoverExistingShortUrl(album.getAlbumUrl());
        recordShortUrlResult(action, "recover", shortUrlResult);
        if (hasShortUrl(shortUrlResult)) {
            album.setShortUrl(shortUrlResult.getShortUrl());
        }
        attribute(action, "remoteTitle")
                .filter(this::hasText)
                .or(() -> attribute(action, "desiredTitle").filter(this::hasText))
                .ifPresent(album::setTitle);
        album.setSyncStatus(SYNCED);
        album.setErrorMessage(null);
        album.setLastSyncedAt(ZonedDateTime.now());
        externalImageAlbumDao.update(album);
        metricsService.recordAlbumAdoption(providerTag(action), "adopted");
        return result(
                action,
                SyncActionStatus.SUCCEEDED,
                "Adopted existing Flickr album into DB; %s".formatted(shortUrlMessage(shortUrlResult)),
                startedAt,
                remoteAlbumId,
                null
        );
    }

    private SyncActionResult adoptRemotePhoto(SyncAction action, String remotePhotoId, LocalDateTime startedAt) {
        Integer externalServiceId = requiredInteger(action, "externalServiceId");
        Integer itemInventoryPhotoId = requiredInteger(action, "itemInventoryPhotoId");
        Optional<ExternalImage> existingRemoteOwner =
                externalImageDao.findByExternalServiceIdAndExternalServiceImageId(externalServiceId, remotePhotoId);
        if (existingRemoteOwner.isPresent()
                && !itemInventoryPhotoId.equals(existingRemoteOwner.get().getItemInventoryPhotoId())) {
            return result(
                    action,
                    SyncActionStatus.FAILED,
                    "Remote photo id [%s] is already linked to item inventory photo [%s]".formatted(
                            remotePhotoId,
                            existingRemoteOwner.get().getItemInventoryPhotoId()
                    ),
                    startedAt,
                    null,
                    remotePhotoId
            );
        }

        ItemInventoryPhoto inventoryPhoto = itemInventoryPhotoDao.findByItemInventoryPhotoId(itemInventoryPhotoId)
                .orElseThrow(() -> new IllegalArgumentException("No item inventory photo found for id [%s]".formatted(itemInventoryPhotoId)));
        ExternalImage image = findOrCreateImage(action, externalServiceId, itemInventoryPhotoId);
        image.setExternalServiceImageId(remotePhotoId);
        attribute(action, "remoteTitle").filter(this::hasText).ifPresent(image::setTitle);
        attribute(action, "remotePhotoUrl").filter(this::hasText).ifPresent(image::setImageUrl);
        image.setMd5AtUpload(inventoryPhoto.getMd5());
        image.setMetadataHashAtSync(inventoryPhoto.getMetadataHash());
        image.setSyncStatus(SYNCED);
        image.setErrorMessage(null);
        image.setUploadedAt(Optional.ofNullable(image.getUploadedAt()).orElse(ZonedDateTime.now()));
        image.setLastSyncedAt(ZonedDateTime.now());
        externalImageDao.upsert(image);
        return result(action, SyncActionStatus.SUCCEEDED, "Adopted existing Flickr photo into DB", startedAt, null, remotePhotoId);
    }

    private SyncActionResult repairAlbumMembership(SyncAction action, Set<String> remotePhotoIds, LocalDateTime startedAt) {
        Integer externalServiceId = requiredInteger(action, "externalServiceId");
        String remoteAlbumId = requiredAlbumId(action);
        ExternalImageAlbum album = findAlbum(action);
        String remotePrimaryPhotoId = attribute(action, "remotePrimaryPhotoId")
                .filter(this::hasText)
                .orElseGet(() -> remotePhotoIds.stream().findFirst().orElse(null));

        externalImageAlbumImageDao.deleteByExternalImageAlbumId(album.getExternalImageAlbumId());
        int sortOrder = 1;
        for (String remotePhotoId : remotePhotoIds) {
            ExternalImage image = externalImageDao.findByExternalServiceIdAndExternalServiceImageId(externalServiceId, remotePhotoId)
                    .orElseThrow(() -> new IllegalStateException("No external image row found for Flickr photo id [%s]".formatted(remotePhotoId)));
            externalImageAlbumImageDao.upsert(ExternalImageAlbumImage.builder()
                    .externalImageAlbumId(album.getExternalImageAlbumId())
                    .externalImageId(image.getExternalImageId())
                    .sortOrder(sortOrder++)
                    .primary(Objects.equals(remotePrimaryPhotoId, remotePhotoId))
                    .build());
        }

        album.setSyncStatus(SYNCED);
        album.setErrorMessage(null);
        album.setLastSyncedAt(ZonedDateTime.now());
        externalImageAlbumDao.update(album);
        return result(action, SyncActionStatus.SUCCEEDED, "Repaired DB Flickr album membership rows", startedAt, remoteAlbumId, remotePrimaryPhotoId);
    }

    private PhotoMetaDataV1 loadPhotoMetaData(ItemInventoryPhoto photo) throws IOException {
        Files.createDirectories(properties.getTempDirectory());
        Path tempFile = Files.createTempFile(properties.getTempDirectory(), "photo-%s-".formatted(photo.getItemInventoryPhotoId()), TEMP_FILE_SUFFIX);
        try {
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

    private ExternalImageAlbum findOrCreateAlbum(SyncAction action, Integer externalServiceId, Integer itemInventoryId) {
        Optional<Long> externalImageAlbumId = longAttribute(action, "externalImageAlbumId");
        if (externalImageAlbumId.isPresent()) {
            return externalImageAlbumDao.findByExternalImageAlbumId(externalImageAlbumId.get())
                    .orElseThrow(() -> new IllegalArgumentException("No external image album found for id [%s]".formatted(externalImageAlbumId.get())));
        }

        return externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(externalServiceId, itemInventoryId)
                .orElseGet(() -> externalImageAlbumDao.insert(ExternalImageAlbum.builder()
                        .externalServiceId(externalServiceId)
                        .itemInventoryId(itemInventoryId)
                        .title(attribute(action, "desiredTitle").orElse(null))
                        .syncStatus(PENDING)
                        .build()));
    }

    private ExternalImage findOrCreateImage(SyncAction action, Integer externalServiceId, Integer itemInventoryPhotoId) {
        Optional<Long> externalImageId = longAttribute(action, "externalImageId");
        if (externalImageId.isPresent()) {
            return externalImageDao.findByExternalImageId(externalImageId.get())
                    .orElseThrow(() -> new IllegalArgumentException("No external image found for id [%s]".formatted(externalImageId.get())));
        }

        return externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(externalServiceId, itemInventoryPhotoId)
                .orElseGet(() -> ExternalImage.builder()
                        .externalServiceId(externalServiceId)
                        .itemInventoryPhotoId(itemInventoryPhotoId)
                        .build());
    }

    private List<SyncedPhoto> syncedPhotos(Integer externalServiceId, Integer itemInventoryId) {
        return itemInventoryPhotoDao.findByItemInventoryId(itemInventoryId).stream()
                .sorted(Comparator.comparing(ItemInventoryPhoto::getItemInventoryPhotoId, Comparator.nullsLast(Integer::compareTo)))
                .map(photo -> externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(externalServiceId, photo.getItemInventoryPhotoId())
                        .filter(image -> hasText(image.getExternalServiceImageId()))
                        .map(image -> new SyncedPhoto(photo, image)))
                .flatMap(Optional::stream)
                .toList();
    }

    private void persistAlbumMembership(ExternalImageAlbum album, List<SyncedPhoto> syncedPhotos) {
        String primaryPhotoId = syncedPhotos.stream()
                .filter(SyncedPhoto::primary)
                .findFirst()
                .or(() -> syncedPhotos.stream().findFirst())
                .map(syncedPhoto -> syncedPhoto.externalImage().getExternalServiceImageId())
                .orElse(null);
        externalImageAlbumImageDao.deleteByExternalImageAlbumId(album.getExternalImageAlbumId());
        for (int i = 0; i < syncedPhotos.size(); i++) {
            SyncedPhoto syncedPhoto = syncedPhotos.get(i);
            externalImageAlbumImageDao.upsert(ExternalImageAlbumImage.builder()
                    .externalImageAlbumId(album.getExternalImageAlbumId())
                    .externalImageId(syncedPhoto.externalImage().getExternalImageId())
                    .sortOrder(i + 1)
                    .primary(Objects.equals(primaryPhotoId, syncedPhoto.externalImage().getExternalServiceImageId()))
                    .build());
        }
    }

    private void persistAlbumMembership(SyncAction action, Set<String> photoIds, String primaryPhotoId) {
        ExternalImageAlbum album = findAlbum(action);
        Integer externalServiceId = requiredInteger(action, "externalServiceId");
        Map<String, ExternalImage> imagesByPhotoId = photoIds.stream()
                .map(photoId -> externalImageDao.findByExternalServiceIdAndExternalServiceImageId(externalServiceId, photoId))
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(
                        ExternalImage::getExternalServiceImageId,
                        Function.identity(),
                        (first, ignored) -> first
                ));

        externalImageAlbumImageDao.deleteByExternalImageAlbumId(album.getExternalImageAlbumId());
        int sortOrder = 1;
        for (String photoId : photoIds) {
            ExternalImage image = Optional.ofNullable(imagesByPhotoId.get(photoId))
                    .orElseThrow(() -> new IllegalStateException("No external image row found for Flickr photo id [%s]".formatted(photoId)));
            externalImageAlbumImageDao.upsert(ExternalImageAlbumImage.builder()
                    .externalImageAlbumId(album.getExternalImageAlbumId())
                    .externalImageId(image.getExternalImageId())
                    .sortOrder(sortOrder++)
                    .primary(Objects.equals(primaryPhotoId, photoId))
                    .build());
        }

        album.setSyncStatus(SYNCED);
        album.setErrorMessage(null);
        album.setLastSyncedAt(ZonedDateTime.now());
        externalImageAlbumDao.update(album);
    }

    private void updateExternalImageMetadata(SyncAction action) {
        ExternalImage image = findImage(action);
        Integer itemInventoryPhotoId = requiredInteger(action, "itemInventoryPhotoId");
        ItemInventoryPhoto photo = itemInventoryPhotoDao.findByItemInventoryPhotoId(itemInventoryPhotoId)
                .orElseThrow(() -> new IllegalArgumentException("No item inventory photo found for id [%s]".formatted(itemInventoryPhotoId)));
        image.setTitle(publishingPolicy.photoTitle(photo));
        image.setMetadataHashAtSync(photo.getMetadataHash());
        image.setSyncStatus(SYNCED);
        image.setErrorMessage(null);
        image.setLastSyncedAt(ZonedDateTime.now());
        externalImageDao.update(image);
    }

    private void updateExternalAlbumMetadata(SyncAction action) {
        ExternalImageAlbum album = findAlbum(action);
        album.setTitle(attribute(action, "desiredTitle").orElse(album.getTitle()));
        album.setSyncStatus(SYNCED);
        album.setErrorMessage(null);
        album.setLastSyncedAt(ZonedDateTime.now());
        externalImageAlbumDao.update(album);
    }

    private void markImageFailed(SyncAction action, String message) {
        ExternalImage image = findImage(action);
        image.setSyncStatus(FAILED);
        image.setErrorMessage(message);
        image.setLastSyncedAt(ZonedDateTime.now());
        externalImageDao.update(image);
    }

    private void markAlbumFailed(SyncAction action, String message) {
        markAlbumFailed(findAlbum(action), message);
    }

    private void markAlbumFailed(ExternalImageAlbum album, String message) {
        album.setSyncStatus(FAILED);
        album.setErrorMessage(message);
        album.setLastSyncedAt(ZonedDateTime.now());
        externalImageAlbumDao.update(album);
    }

    private ExternalImageAlbum findAlbum(SyncAction action) {
        Optional<Long> externalImageAlbumId = longAttribute(action, "externalImageAlbumId");
        if (externalImageAlbumId.isPresent()) {
            Long albumId = externalImageAlbumId.get();
            return externalImageAlbumDao.findByExternalImageAlbumId(albumId)
                    .orElseThrow(() -> new IllegalArgumentException("No external image album found for id [%s]".formatted(albumId)));
        }
        return externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(
                        requiredInteger(action, "externalServiceId"),
                        requiredInteger(action, "itemInventoryId")
                )
                .orElseThrow(() -> new IllegalArgumentException("No external image album found for item inventory [%s]".formatted(requiredInteger(action, "itemInventoryId"))));
    }

    private ExternalImage findImage(SyncAction action) {
        Optional<Long> externalImageId = longAttribute(action, "externalImageId");
        if (externalImageId.isPresent()) {
            Long imageId = externalImageId.get();
            return externalImageDao.findByExternalImageId(imageId)
                    .orElseThrow(() -> new IllegalArgumentException("No external image found for id [%s]".formatted(imageId)));
        }
        return externalImageDao.findByExternalServiceIdAndExternalServiceImageId(
                        requiredInteger(action, "externalServiceId"),
                        requiredPhotoId(action)
                )
                .orElseThrow(() -> new IllegalArgumentException("No external image found for Flickr photo id [%s]".formatted(requiredPhotoId(action))));
    }

    private SyncActionResult responseResult(
            SyncAction action,
            SyncActionStatus status,
            ImageHostingRetryTemplate.AttemptedResponse<?> attemptedResponse,
            String message,
            LocalDateTime startedAt,
            String albumId
    ) {
        PhotoServiceResponse<?> response = attemptedResponse.response();
        return SyncActionResult.builder()
                .actionId(action.getActionId())
                .type(action.getType())
                .status(status)
                .albumId(albumId == null ? action.getAlbumId() : albumId)
                .photoId(action.getPhotoId())
                .responseCode(response.responseCode())
                .errorType(response.errorType())
                .attempts(attemptedResponse.attempts())
                .retried(attemptedResponse.retried())
                .retryable(attemptedResponse.retryable())
                .message(message)
                .startedAt(startedAt)
                .finishedAt(LocalDateTime.now())
                .build();
    }

    private SyncActionResult result(
            SyncAction action,
            SyncActionStatus status,
            String message,
            LocalDateTime startedAt,
            String albumId,
            String photoId
    ) {
        return result(action, status, message, startedAt, albumId, photoId, null);
    }

    private SyncActionResult result(
            SyncAction action,
            SyncActionStatus status,
            String message,
            LocalDateTime startedAt,
            String albumId,
            String photoId,
            ImageHostingRetryTemplate.AttemptedResponse<?> attemptedResponse
    ) {
        return SyncActionResult.builder()
                .actionId(action == null ? null : action.getActionId())
                .type(action == null ? null : action.getType())
                .status(status)
                .albumId(albumId == null && action != null ? action.getAlbumId() : albumId)
                .photoId(photoId == null && action != null ? action.getPhotoId() : photoId)
                .message(message)
                .attempts(attemptedResponse == null ? 0 : attemptedResponse.attempts())
                .retried(attemptedResponse != null && attemptedResponse.retried())
                .retryable(attemptedResponse != null && attemptedResponse.retryable())
                .startedAt(startedAt)
                .finishedAt(LocalDateTime.now())
                .build();
    }

    private Integer requiredInteger(SyncAction action, String attributeName) {
        return attribute(action, attributeName)
                .filter(this::hasText)
                .map(Integer::valueOf)
                .orElseThrow(() -> new IllegalArgumentException("%s is required".formatted(attributeName)));
    }

    private Optional<Long> longAttribute(SyncAction action, String attributeName) {
        return attribute(action, attributeName)
                .filter(this::hasText)
                .map(Long::valueOf);
    }

    private Optional<String> attribute(SyncAction action, String attributeName) {
        return Optional.ofNullable(action)
                .map(SyncAction::getAttributes)
                .map(attributes -> attributes.get(attributeName));
    }

    private String requiredPhotoId(SyncAction action) {
        return Optional.ofNullable(action.getPhotoId())
                .filter(this::hasText)
                .or(() -> attribute(action, "externalServiceImageId").filter(this::hasText))
                .orElseThrow(() -> new IllegalArgumentException("photoId is required"));
    }

    private String requiredAlbumId(SyncAction action) {
        return Optional.ofNullable(action.getAlbumId())
                .filter(this::hasText)
                .or(() -> attribute(action, "externalAlbumId").filter(this::hasText))
                .orElseThrow(() -> new IllegalArgumentException("albumId is required"));
    }

    private Set<String> csvAttribute(SyncAction action, String attributeName) {
        return attribute(action, attributeName)
                .stream()
                .flatMap(value -> List.of(value.split(",")).stream())
                .map(String::trim)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String primaryPhotoId(List<SyncedPhoto> syncedPhotos) {
        return syncedPhotos.stream()
                .filter(SyncedPhoto::primary)
                .findFirst()
                .or(() -> syncedPhotos.stream().findFirst())
                .map(syncedPhoto -> syncedPhoto.externalImage().getExternalServiceImageId())
                .orElseThrow(() -> new IllegalArgumentException("At least one synced photo is required"));
    }

    private void deleteTempFile(PhotoMetaDataV1 photoMetaData) {
        if (photoMetaData == null) {
            return;
        }
        try {
            Files.deleteIfExists(photoMetaData.getAbsolutePath());
        } catch (IOException ignored) {
        }
    }

    private String responseMessage(PhotoServiceResponse<?> response) {
        if (hasText(response.responseMessage())) {
            return response.responseMessage();
        }
        return "Image hosting provider returned response code [%s]".formatted(response.responseCode());
    }

    private String shortUrlMessage(ImageHostingShortUrlResult result) {
        if (result == null) {
            return "short URL not attempted";
        }
        if (result.hasShortUrl()) {
            return "%s [%s]".formatted(result.getMessage(), result.getShortUrl());
        }
        return "%s [%s]".formatted(result.getMessage(), result.getStatus());
    }

    private boolean hasShortUrl(ImageHostingShortUrlResult result) {
        return result != null && result.hasShortUrl();
    }

    private void recordShortUrlResult(
            SyncAction action,
            String operation,
            ImageHostingShortUrlResult result
    ) {
        ImageHostingShortUrlStatus status = Optional.ofNullable(result)
                .map(ImageHostingShortUrlResult::getStatus)
                .orElse(ImageHostingShortUrlStatus.FAILED);
        metricsService.recordShortUrl(providerTag(action), operation, shortUrlResultTag(status));
    }

    private String shortUrlResultTag(ImageHostingShortUrlStatus status) {
        return switch (status) {
            case RECOVERED_EXISTING -> "recovered";
            case GENERATED_NEW -> "generated";
            case LOOKUP_MISS -> "lookup_miss";
            case DISABLED -> "disabled";
            case FAILED -> "failed";
        };
    }

    private String providerTag(SyncAction action) {
        return attribute(action, "provider").filter(this::hasText).orElse("unknown");
    }

    private ImageHostingService imageHostingService() {
        return imageHostingService.orElseThrow(() -> new IllegalStateException("No ImageHostingService bean is configured"));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record SyncedPhoto(ItemInventoryPhoto photo, ExternalImage externalImage) {
        private boolean primary() {
            return photo != null && Boolean.TRUE.equals(photo.getPrimary());
        }
    }
}
