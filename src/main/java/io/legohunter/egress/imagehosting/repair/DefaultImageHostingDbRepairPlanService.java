package io.legohunter.egress.imagehosting.repair;

import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.egress.imagehosting.ImageHostingAlbumIdentity;
import io.legohunter.egress.imagehosting.ImageHostingRetryTemplate;
import io.legohunter.egress.imagehosting.ImageHostingSyncMetricsService;
import io.legohunter.egress.imagehosting.publishing.ImageHostingPublishingPolicy;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingAlbum;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingPhoto;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateReader;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateRequest;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateSnapshot;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumPage;
import io.legohunter.imaging.model.HostedAlbumPhotoSearchRequest;
import io.legohunter.imaging.model.HostedAlbumSearchRequest;
import io.legohunter.imaging.model.HostedPhoto;
import io.legohunter.imaging.model.HostedPhotoPage;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.model.SimplePhotoServiceRequest;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import io.legohunter.imaging.service.sync.model.SyncAction;
import io.legohunter.imaging.service.sync.model.SyncActionSafety;
import io.legohunter.imaging.service.sync.model.SyncActionType;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.imaging.service.sync.model.SyncPlanMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.legohunter.egress.imagehosting.repair.ImageHostingDbRepairAttributes.REMOTE_ADOPTION_AMBIGUOUS;
import static io.legohunter.egress.imagehosting.repair.ImageHostingDbRepairAttributes.REMOTE_ADOPTION_BLOCKED;
import static io.legohunter.egress.imagehosting.repair.ImageHostingDbRepairAttributes.REMOTE_ADOPTION_FAILED;
import static io.legohunter.egress.imagehosting.repair.ImageHostingDbRepairAttributes.REMOTE_ADOPTION_NOT_FOUND;
import static io.legohunter.egress.imagehosting.repair.ImageHostingDbRepairAttributes.REMOTE_ADOPTION_STATUS;

@Service
@RequiredArgsConstructor
public class DefaultImageHostingDbRepairPlanService implements ImageHostingDbRepairPlanService {
    private static final int DEFAULT_PAGE_SIZE = 500;

    private final ImageHostingDesiredStateReader desiredStateReader;
    private final Optional<ImageHostingService> imageHostingService;
    private final ImageHostingRetryTemplate retryTemplate;
    private final ImageHostingPublishingPolicy publishingPolicy;
    private final ImageHostingSyncMetricsService metricsService;

    @Override
    public SyncPlan plan(ImageHostingDbRepairPlanRequest request) {
        if (request == null || request.getItemInventoryId() == null) {
            throw new IllegalArgumentException("itemInventoryId is required");
        }

        ImageHostingDesiredStateSnapshot desiredState = desiredStateReader.read(ImageHostingDesiredStateRequest.builder()
                .itemInventoryId(request.getItemInventoryId())
                .provider(request.getProvider())
                .externalServiceId(request.getExternalServiceId())
                .build());
        PlanActionBuilder actions = new PlanActionBuilder(desiredState);
        DesiredImageHostingAlbum desiredAlbum = desiredState.getAlbum();
        if (desiredAlbum == null) {
            recordAdoptionStatus(desiredState, REMOTE_ADOPTION_BLOCKED);
            actions.add(
                    SyncActionType.REPAIR_ALBUM_ID,
                    SyncActionSafety.BLOCKED,
                    "Desired album state is missing; DB repair cannot discover a remote album",
                    adoptionAttributes(REMOTE_ADOPTION_BLOCKED, Map.of())
            );
            return buildPlan(desiredState, actions);
        }

        RemoteRecoveryState remote;
        try {
            remote = readRemoteState(desiredAlbum, itemUuid(desiredState), request);
        } catch (RemoteRecoveryException e) {
            remote = RemoteRecoveryState.failed(e.getMessage());
        }
        if (!remote.failureMessages().isEmpty()) {
            recordAdoptionStatus(desiredState, REMOTE_ADOPTION_FAILED);
            actions.add(
                    SyncActionType.REPAIR_ALBUM_ID,
                    SyncActionSafety.BLOCKED,
                    "Remote recovery failed; DB repair cannot safely compare DB state to Flickr state",
                    adoptionAttributes(REMOTE_ADOPTION_FAILED, Map.of(
                            "failureMessages", String.join("; ", remote.failureMessages())
                    ))
            );
            return buildPlan(desiredState, actions);
        }
        if (remote.blockedMessage() != null) {
            Optional.ofNullable(remote.blockedAttributes().get(REMOTE_ADOPTION_STATUS))
                    .ifPresent(status -> recordAdoptionStatus(desiredState, status));
            actions.add(
                    SyncActionType.REPAIR_ALBUM_ID,
                    SyncActionSafety.BLOCKED,
                    remote.blockedMessage(),
                    remote.blockedAttributes()
            );
            return buildPlan(desiredState, actions);
        }
        if (remote.album().isEmpty()) {
            recordAdoptionStatus(desiredState, REMOTE_ADOPTION_NOT_FOUND);
            actions.add(
                    SyncActionType.REPAIR_ALBUM_ID,
                    SyncActionSafety.BLOCKED,
                    "No matching remote Flickr album was found for DB repair",
                    adoptionAttributes(REMOTE_ADOPTION_NOT_FOUND, Map.of(
                            "desiredTitle", value(desiredAlbum.getDesiredTitle())
                    ))
            );
            return buildPlan(desiredState, actions);
        }

        HostedAlbum remoteAlbum = remote.album().orElseThrow();
        planAlbumRepair(desiredAlbum, remoteAlbum, actions);
        Map<String, DesiredImageHostingPhoto> mappedPhotosByRemoteId =
                planPhotoRepairs(desiredState, remoteAlbum, remote.photos(), actions);
        planMembershipRepair(desiredState, remoteAlbum, remote.photos(), mappedPhotosByRemoteId, actions);
        return buildPlan(desiredState, actions);
    }

    private void planAlbumRepair(
            DesiredImageHostingAlbum desiredAlbum,
            HostedAlbum remoteAlbum,
            PlanActionBuilder actions
    ) {
        String currentAlbumId = desiredAlbum.getExternalAlbumId();
        if (Objects.equals(currentAlbumId, remoteAlbum.getId())
                && sameText(desiredAlbum.getAlbumUrl(), remoteAlbum.getUrl())) {
            return;
        }

        SyncActionSafety safety = hasText(currentAlbumId) && !Objects.equals(currentAlbumId, remoteAlbum.getId())
                ? SyncActionSafety.REQUIRES_REVIEW
                : SyncActionSafety.SAFE_AUTOMATIC;
        actions.add(
                SyncActionType.REPAIR_ALBUM_ID,
                safety,
                "Repair DB Flickr album linkage from remote album state",
                Map.of(
                        "externalAlbumId", value(currentAlbumId),
                        "remoteAlbumId", value(remoteAlbum.getId()),
                        "remoteAlbumUrl", value(remoteAlbum.getUrl()),
                        "remoteTitle", value(remoteAlbum.getTitle()),
                        "remoteDescription", value(remoteAlbum.getDescription())
                )
        );
    }

    private Map<String, DesiredImageHostingPhoto> planPhotoRepairs(
            ImageHostingDesiredStateSnapshot desiredState,
            HostedAlbum remoteAlbum,
            List<HostedPhoto> remotePhotos,
            PlanActionBuilder actions
    ) {
        Map<String, HostedPhoto> remotePhotosById = remotePhotosById(remotePhotos);
        Map<String, List<HostedPhoto>> remotePhotosByTitle = remotePhotosByTitle(remotePhotos);
        Map<String, DesiredImageHostingPhoto> mappedPhotosByRemoteId = new LinkedHashMap<>();
        Set<String> claimedRemotePhotoIds = desiredState.getPhotos().stream()
                .map(DesiredImageHostingPhoto::getExternalServiceImageId)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (DesiredImageHostingPhoto desiredPhoto : desiredState.getPhotos()) {
            String currentPhotoId = desiredPhoto.getExternalServiceImageId();
            if (hasText(currentPhotoId) && remotePhotosById.containsKey(currentPhotoId)) {
                mappedPhotosByRemoteId.put(currentPhotoId, desiredPhoto);
                continue;
            }

            String desiredTitle = photoTitle(desiredPhoto);
            List<HostedPhoto> titleMatches = remotePhotosByTitle.getOrDefault(normalizeText(desiredTitle), List.of());
            if (titleMatches.size() == 1) {
                HostedPhoto remotePhoto = titleMatches.getFirst();
                if (claimedRemotePhotoIds.contains(remotePhoto.getId()) && !Objects.equals(currentPhotoId, remotePhoto.getId())) {
                    actions.addPhotoAction(
                            SyncActionType.REPAIR_PHOTO_ID,
                            SyncActionSafety.BLOCKED,
                            desiredPhoto,
                            "Remote Flickr photo match is already claimed by another DB photo",
                            photoRepairAttributes(remoteAlbum, remotePhoto, currentPhotoId, desiredTitle)
                    );
                    continue;
                }
                mappedPhotosByRemoteId.put(remotePhoto.getId(), desiredPhoto);
                actions.addPhotoAction(
                        SyncActionType.REPAIR_PHOTO_ID,
                        hasText(currentPhotoId) ? SyncActionSafety.REQUIRES_REVIEW : SyncActionSafety.SAFE_AUTOMATIC,
                        desiredPhoto,
                        hasText(currentPhotoId)
                                ? "Repair stale DB Flickr photo id from unique remote title match"
                                : "Repair missing DB Flickr photo id from unique remote title match",
                        photoRepairAttributes(remoteAlbum, remotePhoto, currentPhotoId, desiredTitle)
                );
                continue;
            }

            if (titleMatches.size() > 1) {
                actions.addPhotoAction(
                        SyncActionType.REPAIR_PHOTO_ID,
                        SyncActionSafety.BLOCKED,
                        desiredPhoto,
                        "Multiple remote Flickr photos match the DB photo title; repair is ambiguous",
                        Map.of(
                                "externalServiceImageId", value(currentPhotoId),
                                "desiredTitle", value(desiredTitle),
                                "matchingRemotePhotoIds", titleMatches.stream()
                                        .map(HostedPhoto::getId)
                                        .filter(this::hasText)
                                        .collect(Collectors.joining(","))
                        )
                );
            }
        }

        return mappedPhotosByRemoteId;
    }

    private Map<String, String> photoRepairAttributes(
            HostedAlbum remoteAlbum,
            HostedPhoto remotePhoto,
            String currentPhotoId,
            String desiredTitle
    ) {
        return Map.of(
                "externalAlbumId", value(remoteAlbum.getId()),
                "externalServiceImageId", value(currentPhotoId),
                "remotePhotoId", value(remotePhoto.getId()),
                "remotePhotoUrl", value(remotePhoto.getUrl()),
                "remoteTitle", value(remotePhoto.getTitle()),
                "remoteDescription", value(remotePhoto.getDescription()),
                "desiredTitle", value(desiredTitle)
        );
    }

    private void planMembershipRepair(
            ImageHostingDesiredStateSnapshot desiredState,
            HostedAlbum remoteAlbum,
            List<HostedPhoto> remotePhotos,
            Map<String, DesiredImageHostingPhoto> mappedPhotosByRemoteId,
            PlanActionBuilder actions
    ) {
        List<String> remotePhotoIds = remotePhotos.stream()
                .map(HostedPhoto::getId)
                .filter(this::hasText)
                .toList();
        if (remotePhotoIds.isEmpty()) {
            return;
        }

        List<String> unmatchedRemotePhotoIds = remotePhotoIds.stream()
                .filter(photoId -> !mappedPhotosByRemoteId.containsKey(photoId))
                .toList();
        if (!unmatchedRemotePhotoIds.isEmpty()) {
            actions.add(
                    SyncActionType.UPDATE_ALBUM_MEMBERSHIP,
                    SyncActionSafety.BLOCKED,
                    "Remote Flickr album contains photos that could not be matched to DB photos; membership repair is unsafe",
                    Map.of(
                            "externalAlbumId", value(remoteAlbum.getId()),
                            "remotePhotoIds", String.join(",", remotePhotoIds),
                            "unmatchedRemotePhotoIds", String.join(",", unmatchedRemotePhotoIds)
                    )
            );
            return;
        }

        List<String> currentMembershipPhotoIds = currentMembershipPhotoIds(desiredState);
        String currentPrimaryPhotoId = currentPrimaryPhotoId(desiredState).orElse(null);
        String remotePrimaryPhotoId = remotePrimaryPhotoId(remoteAlbum, remotePhotos).orElse(null);
        boolean membershipChanged = !remotePhotoIds.equals(currentMembershipPhotoIds);
        boolean primaryChanged = hasText(remotePrimaryPhotoId) && !Objects.equals(remotePrimaryPhotoId, currentPrimaryPhotoId);
        boolean pendingPhotoRepair = actions.actions().stream()
                .anyMatch(action -> SyncActionType.REPAIR_PHOTO_ID.equals(action.getType()) && !action.isBlocked());
        boolean pendingAlbumRepair = actions.actions().stream()
                .anyMatch(action -> SyncActionType.REPAIR_ALBUM_ID.equals(action.getType()) && !action.isBlocked());

        if (!membershipChanged && !primaryChanged && !pendingPhotoRepair && !pendingAlbumRepair) {
            return;
        }

        actions.add(
                SyncActionType.UPDATE_ALBUM_MEMBERSHIP,
                SyncActionSafety.SAFE_AUTOMATIC,
                "Repair DB Flickr album membership rows from remote album state",
                Map.of(
                        "externalAlbumId", value(remoteAlbum.getId()),
                        "remotePhotoIds", String.join(",", remotePhotoIds),
                        "currentPhotoIds", String.join(",", currentMembershipPhotoIds),
                        "remotePrimaryPhotoId", value(remotePrimaryPhotoId),
                        "currentPrimaryPhotoId", value(currentPrimaryPhotoId)
                )
        );
    }

    private RemoteRecoveryState readRemoteState(
            DesiredImageHostingAlbum desiredAlbum,
            String itemUuid,
            ImageHostingDbRepairPlanRequest request
    ) {
        List<HostedAlbum> albums = listAlbums(request);
        String currentAlbumId = desiredAlbum.getExternalAlbumId();
        Optional<HostedAlbum> remoteAlbum;
        if (hasText(currentAlbumId)) {
            remoteAlbum = albums.stream()
                    .filter(album -> Objects.equals(currentAlbumId, album.getId()))
                    .findFirst();
            if (remoteAlbum.isEmpty()) {
                return RemoteRecoveryState.blocked(
                        "DB Flickr album id was not found remotely; repair cannot infer a replacement safely",
                        adoptionAttributes(REMOTE_ADOPTION_BLOCKED, Map.of("externalAlbumId", currentAlbumId))
                );
            }
        } else {
            // An album belongs to this item only when its description carries the item's UUID. The title is the
            // item number and name, which every copy of the same set shares, so a title match alone never adopts.
            List<HostedAlbum> uuidMatches = albums.stream()
                    .filter(album -> ImageHostingAlbumIdentity.carries(album.getDescription(), itemUuid))
                    .toList();
            if (uuidMatches.isEmpty()) {
                return RemoteRecoveryState.notFound();
            }
            if (uuidMatches.size() > 1) {
                return RemoteRecoveryState.blocked(
                        "Multiple remote Flickr albums carry this item's UUID; repair is ambiguous",
                        adoptionAttributes(REMOTE_ADOPTION_AMBIGUOUS, Map.of(
                                "desiredTitle", value(desiredAlbum.getDesiredTitle()),
                                "itemUuid", value(itemUuid),
                                "matchingRemoteAlbumIds", uuidMatches.stream()
                                        .map(HostedAlbum::getId)
                                        .filter(this::hasText)
                                        .collect(Collectors.joining(","))
                        ))
                );
            }
            remoteAlbum = Optional.of(uuidMatches.getFirst());
        }

        HostedAlbum album = remoteAlbum.orElseThrow();
        return new RemoteRecoveryState(
                Optional.of(album),
                listAlbumPhotos(album.getId(), request),
                List.of(),
                null,
                Map.of()
        );
    }

    private List<HostedAlbum> listAlbums(ImageHostingDbRepairPlanRequest request) {
        int page = 1;
        int pageSize = effectivePageSize(request.getAlbumPageSize());
        List<HostedAlbum> albums = new ArrayList<>();
        while (true) {
            int currentPage = page;
            ImageHostingRetryTemplate.AttemptedResponse<HostedAlbumPage> attemptedResponse = retryTemplate.execute(
                    "repairListAlbums",
                    () -> imageHostingService().listAlbums(new SimplePhotoServiceRequest<>(
                            HostedAlbumSearchRequest.builder()
                                    .userId(request.getUserId())
                                    .page(currentPage)
                                    .perPage(pageSize)
                                    .build()
                    ))
            );
            PhotoServiceResponse<HostedAlbumPage> response = attemptedResponse.response();
            if (response.isError()) {
                throw new RemoteRecoveryException(responseMessage(attemptedResponse, "Image hosting provider failed to list albums"));
            }

            HostedAlbumPage albumPage = response.get();
            albums.addAll(Optional.ofNullable(albumPage)
                    .map(HostedAlbumPage::getAlbums)
                    .orElse(List.of()));
            int totalPages = Optional.ofNullable(albumPage)
                    .map(HostedAlbumPage::getPages)
                    .orElse(0);
            if (totalPages <= page || totalPages <= 0) {
                return albums;
            }
            page++;
        }
    }

    private List<HostedPhoto> listAlbumPhotos(String albumId, ImageHostingDbRepairPlanRequest request) {
        int page = 1;
        int pageSize = effectivePageSize(request.getPhotoPageSize());
        List<HostedPhoto> photos = new ArrayList<>();
        while (true) {
            int currentPage = page;
            ImageHostingRetryTemplate.AttemptedResponse<HostedPhotoPage> attemptedResponse = retryTemplate.execute(
                    "repairListAlbumPhotos",
                    () -> imageHostingService().listAlbumPhotos(new SimplePhotoServiceRequest<>(
                            HostedAlbumPhotoSearchRequest.builder()
                                    .albumId(albumId)
                                    .page(currentPage)
                                    .perPage(pageSize)
                                    .build()
                    ))
            );
            PhotoServiceResponse<HostedPhotoPage> response = attemptedResponse.response();
            if (response.isError()) {
                throw new RemoteRecoveryException(responseMessage(attemptedResponse, "Image hosting provider failed to list album photos"));
            }

            HostedPhotoPage photoPage = response.get();
            photos.addAll(Optional.ofNullable(photoPage)
                    .map(HostedPhotoPage::getPhotos)
                    .orElse(List.of()));
            int totalPages = Optional.ofNullable(photoPage)
                    .map(HostedPhotoPage::getPages)
                    .orElse(0);
            if (totalPages <= page || totalPages <= 0) {
                return photos;
            }
            page++;
        }
    }

    private SyncPlan buildPlan(ImageHostingDesiredStateSnapshot desiredState, PlanActionBuilder actions) {
        return SyncPlan.builder()
                .planId("image-hosting-repair-%s-%s-%s".formatted(
                        value(desiredState.getProvider()),
                        value(desiredState.getItemInventoryId()),
                        UUID.randomUUID()
                ))
                .mode(SyncPlanMode.DRY_RUN)
                .actions(actions.actions())
                .build();
    }

    private Map<String, HostedPhoto> remotePhotosById(List<HostedPhoto> photos) {
        return photos.stream()
                .filter(photo -> hasText(photo.getId()))
                .collect(Collectors.toMap(
                        HostedPhoto::getId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
    }

    private Map<String, List<HostedPhoto>> remotePhotosByTitle(List<HostedPhoto> photos) {
        return photos.stream()
                .filter(photo -> hasText(photo.getTitle()))
                .collect(Collectors.groupingBy(
                        photo -> normalizeText(photo.getTitle()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    private List<String> currentMembershipPhotoIds(ImageHostingDesiredStateSnapshot desiredState) {
        Map<Long, String> photoIdsByExternalImageId = desiredState.getPhotos().stream()
                .map(DesiredImageHostingPhoto::getExternalImage)
                .filter(Objects::nonNull)
                .filter(image -> image.getExternalImageId() != null)
                .filter(image -> hasText(image.getExternalServiceImageId()))
                .collect(Collectors.toMap(
                        ExternalImage::getExternalImageId,
                        ExternalImage::getExternalServiceImageId,
                        (first, ignored) -> first
                ));

        return desiredState.getAlbumMemberships().stream()
                .sorted(Comparator.comparing(
                        ExternalImageAlbumImage::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo)
                ))
                .map(ExternalImageAlbumImage::getExternalImageId)
                .map(photoIdsByExternalImageId::get)
                .filter(this::hasText)
                .toList();
    }

    private Optional<String> currentPrimaryPhotoId(ImageHostingDesiredStateSnapshot desiredState) {
        Map<Long, String> photoIdsByExternalImageId = desiredState.getPhotos().stream()
                .map(DesiredImageHostingPhoto::getExternalImage)
                .filter(Objects::nonNull)
                .filter(image -> image.getExternalImageId() != null)
                .filter(image -> hasText(image.getExternalServiceImageId()))
                .collect(Collectors.toMap(
                        ExternalImage::getExternalImageId,
                        ExternalImage::getExternalServiceImageId,
                        (first, ignored) -> first
                ));

        return desiredState.getAlbumMemberships().stream()
                .filter(membership -> Boolean.TRUE.equals(membership.getPrimary()))
                .map(ExternalImageAlbumImage::getExternalImageId)
                .map(photoIdsByExternalImageId::get)
                .filter(this::hasText)
                .findFirst();
    }

    private Optional<String> remotePrimaryPhotoId(HostedAlbum remoteAlbum, List<HostedPhoto> photos) {
        return Optional.ofNullable(remoteAlbum.getPrimaryPhotoId())
                .filter(this::hasText)
                .or(() -> photos.stream()
                        .filter(photo -> Boolean.TRUE.equals(photo.getPrimary()))
                        .map(HostedPhoto::getId)
                        .filter(this::hasText)
                        .findFirst());
    }

    private String photoTitle(DesiredImageHostingPhoto desiredPhoto) {
        return Optional.ofNullable(desiredPhoto.getInventoryPhoto())
                .map(publishingPolicy::photoTitle)
                .orElse("");
    }

    private String responseMessage(ImageHostingRetryTemplate.AttemptedResponse<?> attemptedResponse, String defaultMessage) {
        PhotoServiceResponse<?> response = attemptedResponse.response();
        if (hasText(response.responseMessage())) {
            return withAttempts(response.responseMessage(), attemptedResponse);
        }
        if (response.responseCode() != null) {
            return withAttempts("%s; responseCode=[%s]".formatted(defaultMessage, response.responseCode()), attemptedResponse);
        }
        return withAttempts(defaultMessage, attemptedResponse);
    }

    private String withAttempts(String message, ImageHostingRetryTemplate.AttemptedResponse<?> attemptedResponse) {
        if (!attemptedResponse.retried()) {
            return message;
        }
        return "%s; attempts=[%s]; errorType=[%s]".formatted(
                message,
                attemptedResponse.attempts(),
                attemptedResponse.errorType()
        );
    }

    private ImageHostingService imageHostingService() {
        return imageHostingService.orElseThrow(() -> new IllegalStateException("No ImageHostingService bean is configured"));
    }

    private int effectivePageSize(int pageSize) {
        return pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
    }

    private boolean sameText(String left, String right) {
        return Objects.equals(normalizeText(left), normalizeText(right));
    }

    private String normalizeText(String value) {
        return hasText(value) ? value.trim() : "";
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private Map<String, String> adoptionAttributes(String status, Map<String, String> attributes) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(REMOTE_ADOPTION_STATUS, status);
        values.putAll(attributes);
        return values;
    }

    private void recordAdoptionStatus(ImageHostingDesiredStateSnapshot desiredState, String status) {
        metricsService.recordAlbumAdoption(
                providerTag(desiredState),
                switch (status) {
                    case REMOTE_ADOPTION_NOT_FOUND -> "no_match";
                    case REMOTE_ADOPTION_AMBIGUOUS -> "ambiguous";
                    case REMOTE_ADOPTION_BLOCKED -> "blocked";
                    case REMOTE_ADOPTION_FAILED -> "failed";
                    default -> status == null || status.isBlank() ? "unknown" : status.toLowerCase();
                }
        );
    }

    private String itemUuid(ImageHostingDesiredStateSnapshot desiredState) {
        return desiredState.getInventory() == null ? null : desiredState.getInventory().getUuid();
    }

    private String providerTag(ImageHostingDesiredStateSnapshot desiredState) {
        return Optional.ofNullable(desiredState.getProvider())
                .filter(this::hasText)
                .orElse("unknown");
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
            desiredState.albumOptional()
                    .map(DesiredImageHostingAlbum::getDesiredTitle)
                    .filter(DefaultImageHostingDbRepairPlanService.this::hasText)
                    .ifPresent(desiredTitle -> attributes.put("desiredTitle", desiredTitle));
            return attributes;
        }

        private Map<String, String> photoAttributes(DesiredImageHostingPhoto desiredPhoto) {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("itemInventoryPhotoId", value(desiredPhoto.getItemInventoryPhotoId()));
            Optional.ofNullable(desiredPhoto.getExternalImageId())
                    .ifPresent(externalImageId -> attributes.put("externalImageId", value(externalImageId)));
            attributes.put("primary", Boolean.toString(desiredPhoto.isPrimary()));
            return attributes;
        }

        private String filename(DesiredImageHostingPhoto desiredPhoto) {
            return Optional.ofNullable(desiredPhoto.getInventoryPhoto())
                    .map(photo -> photo.getFileName())
                    .orElse(null);
        }

        private List<SyncAction> actions() {
            return actions;
        }
    }

    private record RemoteRecoveryState(
            Optional<HostedAlbum> album,
            List<HostedPhoto> photos,
            List<String> failureMessages,
            String blockedMessage,
            Map<String, String> blockedAttributes
    ) {
        private static RemoteRecoveryState notFound() {
            return new RemoteRecoveryState(Optional.empty(), List.of(), List.of(), null, Map.of());
        }

        private static RemoteRecoveryState blocked(String message, Map<String, String> attributes) {
            return new RemoteRecoveryState(Optional.empty(), List.of(), List.of(), message, attributes);
        }

        private static RemoteRecoveryState failed(String message) {
            return new RemoteRecoveryState(Optional.empty(), List.of(), List.of(message), null, Map.of());
        }
    }

    private static class RemoteRecoveryException extends RuntimeException {
        private RemoteRecoveryException(String message) {
            super(message);
        }
    }
}
