package io.legohunter.egress.imagehosting.shorturl;

import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static io.legohunter.egress.imagehosting.shorturl.ImageHostingShortUrlStatus.CALCULATED;
import static io.legohunter.egress.imagehosting.shorturl.ImageHostingShortUrlStatus.UNAVAILABLE;

/**
 * The short link stored in {@code external_image_album.short_url} for a Flickr album: Flickr's own
 * {@code flic.kr/s/} link, calculated from the album id in every environment (HOMELAB-128). It replaced the bit.ly
 * links, which only production made. The link rule everywhere else stays "short link, else the album's address".
 */
@Service
@RequiredArgsConstructor
public class ImageHostingShortUrlService {
    private final ShortAlbumLinkCheck linkCheck;
    private final ImageHostingSyncProperties imageHostingProperties;

    public ImageHostingShortUrlResult shortUrlFor(String albumId) {
        return FlickrShortAlbumLink.of(albumId)
                .map(shortUrl -> result(CALCULATED, albumId, shortUrl, "Calculated Flickr short album link"))
                .orElseGet(() -> result(UNAVAILABLE, albumId, null, "No Flickr album id to make a short link from"));
    }

    /**
     * Opens a calculated link once, as a buyer would, when this environment checks new links. Empty when there is
     * nothing to check or checking is off.
     */
    public Optional<ShortAlbumLinkCheck.Outcome> check(ImageHostingShortUrlResult result) {
        if (result == null || !result.hasShortUrl() || !imageHostingProperties.getShortUrl().isCheckEnabled()) {
            return Optional.empty();
        }
        return Optional.of(linkCheck.check(result.getShortUrl(), result.getAlbumId()));
    }

    private ImageHostingShortUrlResult result(
            ImageHostingShortUrlStatus status,
            String albumId,
            String shortUrl,
            String message
    ) {
        return ImageHostingShortUrlResult.builder()
                .status(status)
                .albumId(albumId)
                .shortUrl(shortUrl)
                .message(message)
                .build();
    }
}
