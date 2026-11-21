package io.legohunter.egress.imagehosting.shorturl;

import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImageHostingShortUrlServiceTest {
    private ShortAlbumLinkCheck linkCheck;
    private ImageHostingSyncProperties properties;
    private ImageHostingShortUrlService service;

    @BeforeEach
    void setUp() {
        linkCheck = mock(ShortAlbumLinkCheck.class);
        properties = new ImageHostingSyncProperties();
        service = new ImageHostingShortUrlService(linkCheck, properties);
    }

    @Test
    void shortUrlForCalculatesFlickrsLinkFromTheAlbumId() {
        ImageHostingShortUrlResult result = service.shortUrlFor("72177720336783526");

        assertThat(result.getStatus()).isEqualTo(ImageHostingShortUrlStatus.CALCULATED);
        assertThat(result.getShortUrl()).isEqualTo("https://flic.kr/s/aHBqjDaRCA");
        assertThat(result.getAlbumId()).isEqualTo("72177720336783526");
    }

    @Test
    void shortUrlForIsUnavailableWithoutANumericAlbumId() {
        assertThat(service.shortUrlFor(null).getStatus()).isEqualTo(ImageHostingShortUrlStatus.UNAVAILABLE);
        assertThat(service.shortUrlFor("album-100").hasShortUrl()).isFalse();
    }

    @Test
    void checkOpensTheLinkOnlyWhenTurnedOn() {
        ImageHostingShortUrlResult result = service.shortUrlFor("72177720336783526");

        assertThat(service.check(result)).isEmpty();
        verifyNoInteractions(linkCheck);

        properties.getShortUrl().setCheckEnabled(true);
        when(linkCheck.check("https://flic.kr/s/aHBqjDaRCA", "72177720336783526"))
                .thenReturn(ShortAlbumLinkCheck.Outcome.RESOLVES);

        assertThat(service.check(result)).contains(ShortAlbumLinkCheck.Outcome.RESOLVES);
        verify(linkCheck).check("https://flic.kr/s/aHBqjDaRCA", "72177720336783526");
    }

    @Test
    void checkSkipsAResultWithoutALink() {
        properties.getShortUrl().setCheckEnabled(true);

        assertThat(service.check(service.shortUrlFor(""))).isEmpty();
        verifyNoInteractions(linkCheck);
    }
}
