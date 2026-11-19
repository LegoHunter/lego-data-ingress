package io.legohunter.egress.imagehosting.shorturl;

import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.imaging.bitly.config.BitlyProperties;
import io.legohunter.imaging.bitly.impl.BitlinksService;
import io.legohunter.imaging.bitly.model.bitly.Bitlink;
import io.legohunter.imaging.bitly.model.bitly.BitlinksPage;
import io.legohunter.imaging.bitly.model.bitly.Pagination;
import io.legohunter.imaging.bitly.model.bitly.ShortenRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.legohunter.egress.imagehosting.shorturl.ImageHostingShortUrlStatus.DISABLED;
import static io.legohunter.egress.imagehosting.shorturl.ImageHostingShortUrlStatus.FAILED;
import static io.legohunter.egress.imagehosting.shorturl.ImageHostingShortUrlStatus.GENERATED_NEW;
import static io.legohunter.egress.imagehosting.shorturl.ImageHostingShortUrlStatus.LOOKUP_MISS;
import static io.legohunter.egress.imagehosting.shorturl.ImageHostingShortUrlStatus.RECOVERED_EXISTING;

@Service
@Slf4j
@RequiredArgsConstructor
public class ImageHostingShortUrlService {
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final String ARCHIVED_BOTH = "both";
    private static final Pattern FLICKR_ALBUM_ID_PATTERN =
            Pattern.compile("/(?:albums|sets)/([^/?#]+)/?", Pattern.CASE_INSENSITIVE);

    private final Optional<BitlinksService> bitlinksService;
    private final BitlyProperties bitlyProperties;
    private final ImageHostingSyncProperties imageHostingProperties;

    private BitlyShortUrlLookup cachedLookup;

    public ImageHostingShortUrlResult recoverExistingShortUrl(String albumUrl) {
        if (!hasText(albumUrl)) {
            return result(LOOKUP_MISS, albumUrl, null, "Flickr album URL is missing");
        }
        if (!shortLinksEnabled()) {
            return result(DISABLED, albumUrl, null, "Short links are turned off in this environment");
        }
        if (!bitlyReadEnabled()) {
            return result(DISABLED, albumUrl, null, "Bitly lookup is disabled or not configured");
        }

        try {
            return lookup().find(albumUrl)
                    .map(shortUrl -> result(RECOVERED_EXISTING, albumUrl, shortUrl, "Recovered existing Bitly short URL"))
                    .orElseGet(() -> result(LOOKUP_MISS, albumUrl, null, "No matching Bitly short URL found"));
        } catch (RuntimeException e) {
            log.warn("image_hosting.short_url.recover.failed albumUrl={} message={}", albumUrl, e.getMessage(), e);
            return result(FAILED, albumUrl, null, e.getMessage());
        }
    }

    public ImageHostingShortUrlResult generateShortUrl(String albumUrl) {
        if (!hasText(albumUrl)) {
            return result(LOOKUP_MISS, albumUrl, null, "Flickr album URL is missing");
        }
        if (!shortLinksEnabled()) {
            return result(DISABLED, albumUrl, null, "Short links are turned off in this environment");
        }
        if (!bitlyWriteEnabled()) {
            return result(DISABLED, albumUrl, null, "Bitly shortening is disabled or not configured");
        }

        try {
            ShortenRequest request = new ShortenRequest();
            request.setLongUrl(albumUrl);
            if (hasText(bitlyProperties.getGroupGuid())) {
                request.setGroupGuid(bitlyProperties.getGroupGuid());
            }
            Bitlink bitlink = bitlinksService().shorten(request);
            String shortUrl = Optional.ofNullable(bitlink)
                    .map(Bitlink::getLink)
                    .filter(this::hasText)
                    .orElse(null);
            if (!hasText(shortUrl)) {
                return result(FAILED, albumUrl, null, "Bitly did not return a short URL");
            }
            cache(shortUrl, albumUrl);
            return result(GENERATED_NEW, albumUrl, shortUrl, "Generated new Bitly short URL");
        } catch (RuntimeException e) {
            log.warn("image_hosting.short_url.generate.failed albumUrl={} message={}", albumUrl, e.getMessage(), e);
            return result(FAILED, albumUrl, null, e.getMessage());
        }
    }

    private BitlyShortUrlLookup lookup() {
        if (cachedLookup == null) {
            cachedLookup = loadLookup();
        }
        return cachedLookup;
    }

    private BitlyShortUrlLookup loadLookup() {
        BitlyShortUrlLookup lookup = new BitlyShortUrlLookup();
        String searchAfter = null;
        while (true) {
            BitlinksPage page = bitlinksService().listBitlinks(
                    bitlyProperties.getGroupGuid(),
                    DEFAULT_PAGE_SIZE,
                    searchAfter,
                    ARCHIVED_BOTH
            );
            Optional.ofNullable(page)
                    .map(BitlinksPage::getLinks)
                    .orElse(List.of())
                    .stream()
                    .filter(bitlink -> hasText(bitlink.getLongUrl()))
                    .filter(bitlink -> hasText(bitlink.getLink()))
                    .forEach(bitlink -> lookup.add(bitlink.getLongUrl(), bitlink.getLink()));

            searchAfter = Optional.ofNullable(page)
                    .map(BitlinksPage::getPagination)
                    .map(Pagination::getSearchAfter)
                    .filter(this::hasText)
                    .orElse(null);
            if (!hasText(searchAfter)) {
                return lookup;
            }
        }
    }

    private void cache(String shortUrl, String albumUrl) {
        if (cachedLookup != null) {
            cachedLookup.add(albumUrl, shortUrl);
        }
    }

    /** Off means no call to the link service at all, neither to make a link nor to look one up. */
    private boolean shortLinksEnabled() {
        return imageHostingProperties.getShortUrl().isEnabled();
    }

    private boolean bitlyReadEnabled() {
        return bitlyWriteEnabled() && hasText(bitlyProperties.getGroupGuid());
    }

    private boolean bitlyWriteEnabled() {
        return bitlinksService.isPresent() && hasText(bitlyProperties.getAccessToken());
    }

    private BitlinksService bitlinksService() {
        return bitlinksService.get();
    }

    private ImageHostingShortUrlResult result(
            ImageHostingShortUrlStatus status,
            String albumUrl,
            String shortUrl,
            String message
    ) {
        return ImageHostingShortUrlResult.builder()
                .status(status)
                .albumUrl(albumUrl)
                .shortUrl(shortUrl)
                .message(message)
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeUrl(String value) {
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private Optional<String> flickrAlbumId(String value) {
        Matcher matcher = FLICKR_ALBUM_ID_PATTERN.matcher(normalizeUrl(value));
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private class BitlyShortUrlLookup {
        private final Map<String, String> shortUrlsByLongUrl = new LinkedHashMap<>();
        private final Map<String, String> shortUrlsByFlickrAlbumId = new LinkedHashMap<>();

        private void add(String longUrl, String shortUrl) {
            shortUrlsByLongUrl.putIfAbsent(normalizeUrl(longUrl), shortUrl);
            flickrAlbumId(longUrl).ifPresent(albumId -> shortUrlsByFlickrAlbumId.putIfAbsent(albumId, shortUrl));
        }

        private Optional<String> find(String albumUrl) {
            String normalizedAlbumUrl = normalizeUrl(albumUrl);
            String exactMatch = shortUrlsByLongUrl.get(normalizedAlbumUrl);
            if (hasText(exactMatch)) {
                return Optional.of(exactMatch);
            }
            return flickrAlbumId(albumUrl).map(shortUrlsByFlickrAlbumId::get).filter(ImageHostingShortUrlService.this::hasText);
        }
    }
}
