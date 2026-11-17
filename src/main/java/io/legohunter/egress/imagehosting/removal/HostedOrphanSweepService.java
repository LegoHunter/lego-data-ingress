package io.legohunter.egress.imagehosting.removal;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.egress.imagehosting.ImageHostingRetryTemplate;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumPage;
import io.legohunter.imaging.model.HostedAlbumPhotoSearchRequest;
import io.legohunter.imaging.model.HostedAlbumSearchRequest;
import io.legohunter.imaging.model.HostedPhoto;
import io.legohunter.imaging.model.HostedPhotoPage;
import io.legohunter.imaging.model.HostedPhotoSearchRequest;
import io.legohunter.imaging.model.PhotoServiceErrorType;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.model.SimplePhotoServiceRequest;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import io.legohunter.lego.data.environment.LegoEnvironmentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Finds, and on request deletes, what a lower environment's image host account holds that no inventory item owns
 * (HOMELAB-113).
 *
 * <p>The database is the system of record. A lower-environment account fills with photos the database no longer
 * knows: after the database is reset, or after a test upload outside the system. On a free account that matters,
 * because the host deletes the oldest photos above its item limit. This sweep lists every photo on the account
 * (each album's photos, then the photos in no album), and a photo with no {@code external_image} row is an
 * orphan.
 *
 * <p>Never in production: that account holds albums made by the legacy system, which this database does not know
 * yet and which would all look like orphans.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HostedOrphanSweepService {
    private static final int PAGE_SIZE = 500;

    private final Optional<ImageHostingService> imageHostingService;
    private final ExternalImageDao externalImageDao;
    private final ExternalImageAlbumDao externalImageAlbumDao;
    private final ImageHostingSyncProperties properties;
    private final ImageHostingRetryTemplate retryTemplate;
    private final LegoEnvironmentProperties environment;

    /**
     * @param apply      false lists the orphans; true also deletes them
     * @param maxDeletes the most photos one run deletes
     */
    public OrphanSweepReport sweep(boolean apply, int maxDeletes) {
        if (environment.isProduction()) {
            throw new ProductionRemovalRefusedException(
                    "Refusing to sweep the image host for orphans in production: this is a lower-environment operation"
            );
        }
        ImageHostingService hosting = imageHostingService.orElseThrow(
                () -> new IllegalStateException("No image hosting service is configured")
        );
        Integer externalServiceId = properties.resolveProvider(null, null).externalServiceId();

        // Read the whole account before deleting anything: deleting while paging would shift the pages.
        Map<String, OrphanSweepReport.OrphanPhoto> orphans = new LinkedHashMap<>();
        List<OrphanSweepReport.UnknownAlbum> unknownAlbums = new ArrayList<>();
        int photosSeen = 0;

        List<HostedAlbum> albums = readAll(
                page -> hosting.listAlbums(new SimplePhotoServiceRequest<>(
                        HostedAlbumSearchRequest.builder().page(page).perPage(PAGE_SIZE).build())),
                "listAlbums",
                HostedAlbumPage::getAlbums,
                HostedAlbumPage::getPages
        );
        for (HostedAlbum album : albums) {
            List<HostedPhoto> photos = readAll(
                    page -> hosting.listAlbumPhotos(new SimplePhotoServiceRequest<>(
                            HostedAlbumPhotoSearchRequest.builder().albumId(album.getId()).page(page).perPage(PAGE_SIZE).build())),
                    "listAlbumPhotos",
                    HostedPhotoPage::getPhotos,
                    HostedPhotoPage::getPages
            );
            photosSeen += photos.size();
            int known = 0;
            for (HostedPhoto photo : photos) {
                if (isKnown(externalServiceId, photo.getId())) {
                    known++;
                } else {
                    orphans.putIfAbsent(photo.getId(),
                            new OrphanSweepReport.OrphanPhoto(photo.getId(), photo.getTitle(), album.getId()));
                }
            }
            if (externalImageAlbumDao.findByExternalServiceIdAndExternalAlbumId(externalServiceId, album.getId()).isEmpty()) {
                unknownAlbums.add(new OrphanSweepReport.UnknownAlbum(album.getId(), album.getTitle(), photos.size(), known));
            }
        }

        List<HostedPhoto> loosePhotos = readAll(
                page -> hosting.listPhotosNotInAlbum(new SimplePhotoServiceRequest<>(
                        HostedPhotoSearchRequest.builder().page(page).perPage(PAGE_SIZE).build())),
                "listPhotosNotInAlbum",
                HostedPhotoPage::getPhotos,
                HostedPhotoPage::getPages
        );
        photosSeen += loosePhotos.size();
        for (HostedPhoto photo : loosePhotos) {
            if (!isKnown(externalServiceId, photo.getId())) {
                orphans.putIfAbsent(photo.getId(), new OrphanSweepReport.OrphanPhoto(photo.getId(), photo.getTitle(), null));
            }
        }

        List<String> deleted = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (apply) {
            for (String photoId : orphans.keySet()) {
                if (deleted.size() >= Math.max(0, maxDeletes)) {
                    break;
                }
                PhotoServiceResponse<Void> response = retryTemplate.execute(
                        "deletePhoto",
                        () -> hosting.deletePhoto(new SimplePhotoServiceRequest<>(photoId))
                ).response();
                if (response.isError() && response.errorType() != PhotoServiceErrorType.PHOTO_NOT_FOUND) {
                    failures.add("%s: %s".formatted(photoId, response.responseMessage()));
                } else {
                    deleted.add(photoId);
                }
            }
        }
        int remaining = apply ? orphans.size() - deleted.size() - failures.size() : orphans.size();
        log.info(
                "image_hosting.orphan_sweep.completed environment={} apply={} albumsSeen={} photosSeen={} orphanPhotos={} unknownAlbums={} deleted={} failed={} remaining={}",
                environment.getCode(),
                apply,
                albums.size(),
                photosSeen,
                orphans.size(),
                unknownAlbums.size(),
                deleted.size(),
                failures.size(),
                remaining
        );
        return new OrphanSweepReport(
                environment.getCode(),
                apply,
                albums.size(),
                photosSeen,
                List.copyOf(orphans.values()),
                unknownAlbums,
                deleted,
                failures,
                remaining
        );
    }

    private boolean isKnown(Integer externalServiceId, String hostedPhotoId) {
        return externalImageDao.findByExternalServiceIdAndExternalServiceImageId(externalServiceId, hostedPhotoId).isPresent();
    }

    private <P, T> List<T> readAll(
            IntFunction<PhotoServiceResponse<P>> readPage,
            String operation,
            java.util.function.Function<P, List<T>> items,
            java.util.function.Function<P, Integer> pages
    ) {
        List<T> all = new ArrayList<>();
        int page = 1;
        while (true) {
            int currentPage = page;
            PhotoServiceResponse<P> response = retryTemplate.execute(operation, () -> readPage.apply(currentPage)).response();
            if (response.isError()) {
                // A partial listing would make known photos look like orphans, so stop without deleting anything.
                throw new IllegalStateException(
                        "Image host listing failed (%s, page %s): %s".formatted(operation, page, response.responseMessage())
                );
            }
            P body = response.get();
            if (body == null) {
                return all;
            }
            all.addAll(Optional.ofNullable(items.apply(body)).orElse(List.of()));
            int totalPages = Optional.ofNullable(pages.apply(body)).orElse(0);
            if (totalPages <= page) {
                return all;
            }
            page++;
        }
    }
}
