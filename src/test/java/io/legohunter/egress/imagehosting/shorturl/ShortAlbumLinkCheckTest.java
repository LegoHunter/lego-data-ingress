package io.legohunter.egress.imagehosting.shorturl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortAlbumLinkCheckTest {
    private static final String SHORT_URL = "https://flic.kr/s/aHBqjDaRCA";
    private static final String ALBUM_ID = "72177720336783526";

    @Test
    void endsOnAlbumAcceptsTheAlbumPageInEitherForm() {
        assertThat(ShortAlbumLinkCheck.endsOnAlbum(
                URI.create("https://www.flickr.com/photos/205153404@N04/sets/72177720336783526/"), ALBUM_ID)).isTrue();
        assertThat(ShortAlbumLinkCheck.endsOnAlbum(
                URI.create("https://www.flickr.com/photos/205153404@N04/albums/72177720336783526"), ALBUM_ID)).isTrue();
    }

    @Test
    void endsOnAlbumRejectsAnotherAlbumOrPage() {
        assertThat(ShortAlbumLinkCheck.endsOnAlbum(
                URI.create("https://www.flickr.com/photos/musephotos/sets/72177720336783527/"), ALBUM_ID)).isFalse();
        assertThat(ShortAlbumLinkCheck.endsOnAlbum(
                URI.create("https://www.flickr.com/photos/x/sets/721777203367835260/"), ALBUM_ID)).isFalse();
        assertThat(ShortAlbumLinkCheck.endsOnAlbum(
                URI.create("https://www.flickr.com/short_urls.gne?photoset=aHBqjDaRCA"), ALBUM_ID)).isFalse();
        assertThat(ShortAlbumLinkCheck.endsOnAlbum(null, ALBUM_ID)).isFalse();
    }

    @Test
    void checkResolvesWhenTheLinkEndsOnTheAlbum() throws Exception {
        ShortAlbumLinkCheck check = new ShortAlbumLinkCheck(client(200,
                "https://www.flickr.com/photos/205153404@N04/sets/72177720336783526/"));

        assertThat(check.check(SHORT_URL, ALBUM_ID)).isEqualTo(ShortAlbumLinkCheck.Outcome.RESOLVES);
    }

    @Test
    void checkReportsWrongPageForANotFoundOrAnotherAlbum() throws Exception {
        assertThat(new ShortAlbumLinkCheck(client(404, "https://www.flickr.com/short_urls.gne?photoset=aHBqjDaRCA"))
                .check(SHORT_URL, ALBUM_ID)).isEqualTo(ShortAlbumLinkCheck.Outcome.WRONG_PAGE);
        assertThat(new ShortAlbumLinkCheck(client(200, "https://www.flickr.com/photos/musephotos/sets/72177720336783527/"))
                .check(SHORT_URL, ALBUM_ID)).isEqualTo(ShortAlbumLinkCheck.Outcome.WRONG_PAGE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void checkReportsUnreachableWhenFlickrCannotBeReached() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("connect timed out"));

        assertThat(new ShortAlbumLinkCheck(client).check(SHORT_URL, ALBUM_ID))
                .isEqualTo(ShortAlbumLinkCheck.Outcome.UNREACHABLE);
    }

    @SuppressWarnings("unchecked")
    private static HttpClient client(int status, String finalUri) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.uri()).thenReturn(URI.create(finalUri));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);
        return client;
    }
}
