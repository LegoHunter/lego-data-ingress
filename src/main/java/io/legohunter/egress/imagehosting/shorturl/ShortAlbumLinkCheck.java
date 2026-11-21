package io.legohunter.egress.imagehosting.shorturl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Opens a short album link the way a buyer would (no sign-in) and tells whether it ends on the album. It is the
 * safety net for {@link FlickrShortAlbumLink}, whose album form Flickr does not document: if Flickr ever changes
 * it, the WARN and the {@code image_hosting_short_url{operation="check"}} counter show it. The link is kept either
 * way; it is only a check.
 */
@Slf4j
@Component
public class ShortAlbumLinkCheck {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = "lego-data-ingress";

    private final HttpClient httpClient;

    public ShortAlbumLinkCheck() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build());
    }

    ShortAlbumLinkCheck(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public Outcome check(String shortUrl, String albumId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(shortUrl))
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200 && endsOnAlbum(response.uri(), albumId)) {
                return Outcome.RESOLVES;
            }
            log.warn("image_hosting.short_url.check.wrong_page shortUrl={} albumId={} status={} endedAt={}",
                    shortUrl, albumId, response.statusCode(), response.uri());
            return Outcome.WRONG_PAGE;
        } catch (IOException | RuntimeException e) {
            log.warn("image_hosting.short_url.check.unreachable shortUrl={} albumId={} error={}",
                    shortUrl, albumId, e.getClass().getSimpleName());
            return Outcome.UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.UNREACHABLE;
        }
    }

    /** Whether {@code uri} is the album's page: {@code …/sets/<albumId>/} or {@code …/albums/<albumId>/}. */
    static boolean endsOnAlbum(URI uri, String albumId) {
        if (uri == null || uri.getPath() == null || albumId == null) {
            return false;
        }
        return Pattern.compile("/(?:sets|albums)/" + Pattern.quote(albumId.strip()) + "(?:/|$)")
                .matcher(uri.getPath())
                .find();
    }

    public enum Outcome {
        /** The link opened the album. */
        RESOLVES,
        /** The link answered, but not with the album's page: wrong album, not found, or a private album. */
        WRONG_PAGE,
        /** Flickr could not be reached; says nothing about the link. */
        UNREACHABLE
    }
}
