package io.legohunter.egress.imagehosting.remote;

import io.legohunter.egress.imagehosting.ImageHostingRetryTemplate;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumPage;
import io.legohunter.imaging.model.HostedAlbumPhotoSearchRequest;
import io.legohunter.imaging.model.HostedAlbumSearchRequest;
import io.legohunter.imaging.model.HostedPhoto;
import io.legohunter.imaging.model.HostedPhotoPage;
import io.legohunter.imaging.model.HostedPhotoSearchRequest;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.model.SimplePhotoServiceRequest;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Reads whole listings from the image host account: every album, every photo of one album, every photo in no
 * album. Each read follows the host's pages to the end.
 *
 * <p>A listing is all or nothing. A partial one would make content that exists look missing, so a failed page
 * throws {@link HostedListingException} and the caller gets no list at all.
 */
@Component
@RequiredArgsConstructor
public class HostedAccountListing {
    private static final int PAGE_SIZE = 500;

    private final Optional<ImageHostingService> imageHostingService;
    private final ImageHostingRetryTemplate retryTemplate;

    public List<HostedAlbum> albums() {
        ImageHostingService hosting = hosting();
        return readAll(
                page -> hosting.listAlbums(new SimplePhotoServiceRequest<>(
                        HostedAlbumSearchRequest.builder().page(page).perPage(PAGE_SIZE).build())),
                "listAlbums",
                HostedAlbumPage::getAlbums,
                HostedAlbumPage::getPages
        );
    }

    public List<HostedPhoto> albumPhotos(String albumId) {
        ImageHostingService hosting = hosting();
        return readAll(
                page -> hosting.listAlbumPhotos(new SimplePhotoServiceRequest<>(
                        HostedAlbumPhotoSearchRequest.builder().albumId(albumId).page(page).perPage(PAGE_SIZE).build())),
                "listAlbumPhotos",
                HostedPhotoPage::getPhotos,
                HostedPhotoPage::getPages
        );
    }

    public List<HostedPhoto> photosNotInAlbum() {
        ImageHostingService hosting = hosting();
        return readAll(
                page -> hosting.listPhotosNotInAlbum(new SimplePhotoServiceRequest<>(
                        HostedPhotoSearchRequest.builder().page(page).perPage(PAGE_SIZE).build())),
                "listPhotosNotInAlbum",
                HostedPhotoPage::getPhotos,
                HostedPhotoPage::getPages
        );
    }

    private ImageHostingService hosting() {
        return imageHostingService.orElseThrow(
                () -> new IllegalStateException("No image hosting service is configured")
        );
    }

    private <P, T> List<T> readAll(
            IntFunction<PhotoServiceResponse<P>> readPage,
            String operation,
            Function<P, List<T>> items,
            Function<P, Integer> pages
    ) {
        List<T> all = new ArrayList<>();
        int page = 1;
        while (true) {
            int currentPage = page;
            PhotoServiceResponse<P> response = retryTemplate.execute(operation, () -> readPage.apply(currentPage)).response();
            if (response.isError()) {
                throw new HostedListingException(
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

    /** A listing could not be read to the end. Extends {@link IllegalStateException}, as callers expect. */
    public static class HostedListingException extends IllegalStateException {
        public HostedListingException(String message) {
            super(message);
        }
    }
}
