package io.legohunter.egress.imagehosting.shorturl;

import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.imaging.bitly.config.BitlyProperties;
import io.legohunter.imaging.bitly.impl.BitlinksService;
import io.legohunter.imaging.bitly.model.bitly.Bitlink;
import io.legohunter.imaging.bitly.model.bitly.BitlinksPage;
import io.legohunter.imaging.bitly.model.bitly.Pagination;
import io.legohunter.imaging.bitly.model.bitly.ShortenRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImageHostingShortUrlServiceTest {
    private static final String ALBUM_URL = "https://www.flickr.com/photos/example/albums/721577100";
    private static final String GROUP_GUID = "group-1";
    private static final String SHORT_URL = "https://bit.ly/album-100";

    private BitlinksService bitlinksService;
    private BitlyProperties bitlyProperties;
    private ImageHostingSyncProperties imageHostingProperties;
    private ImageHostingShortUrlService service;

    @BeforeEach
    void setUp() {
        bitlinksService = mock(BitlinksService.class);
        bitlyProperties = new BitlyProperties();
        bitlyProperties.setAccessToken("token");
        bitlyProperties.setGroupGuid(GROUP_GUID);
        imageHostingProperties = new ImageHostingSyncProperties();
        imageHostingProperties.getShortUrl().setEnabled(true);
        service = new ImageHostingShortUrlService(
                Optional.of(bitlinksService),
                bitlyProperties,
                imageHostingProperties
        );
    }

    @Test
    void recoverExistingShortUrlPagesThroughBitlyAndMatchesByAlbumId() {
        when(bitlinksService.listBitlinks(GROUP_GUID, 100, null, "both"))
                .thenReturn(page(List.of(), "cursor-2"));
        when(bitlinksService.listBitlinks(GROUP_GUID, 100, "cursor-2", "both"))
                .thenReturn(page(List.of(bitlink(
                        "https://www.flickr.com/photos/example/sets/721577100/",
                        SHORT_URL
                )), null));

        ImageHostingShortUrlResult result = service.recoverExistingShortUrl(ALBUM_URL);

        assertThat(result)
                .extracting(
                        ImageHostingShortUrlResult::getStatus,
                        ImageHostingShortUrlResult::getAlbumUrl,
                        ImageHostingShortUrlResult::getShortUrl
                )
                .containsExactly(ImageHostingShortUrlStatus.RECOVERED_EXISTING, ALBUM_URL, SHORT_URL);
        assertThat(result.getMessage()).isEqualTo("Recovered existing Bitly short URL");
        assertThat(result.hasShortUrl()).isTrue();
        verify(bitlinksService).listBitlinks(GROUP_GUID, 100, null, "both");
        verify(bitlinksService).listBitlinks(GROUP_GUID, 100, "cursor-2", "both");
    }

    @Test
    void recoverExistingShortUrlMatchesExactNormalizedLongUrl() {
        when(bitlinksService.listBitlinks(GROUP_GUID, 100, null, "both"))
                .thenReturn(page(List.of(bitlink(ALBUM_URL + "/", SHORT_URL)), null));

        ImageHostingShortUrlResult result = service.recoverExistingShortUrl(" " + ALBUM_URL + "///");

        assertThat(result.getStatus()).isEqualTo(ImageHostingShortUrlStatus.RECOVERED_EXISTING);
        assertThat(result.getShortUrl()).isEqualTo(SHORT_URL);
    }

    @Test
    void recoverExistingShortUrlUsesCachedLookupAfterFirstRead() {
        when(bitlinksService.listBitlinks(GROUP_GUID, 100, null, "both"))
                .thenReturn(page(List.of(bitlink(ALBUM_URL, SHORT_URL)), null));

        ImageHostingShortUrlResult firstResult = service.recoverExistingShortUrl(ALBUM_URL);
        ImageHostingShortUrlResult secondResult = service.recoverExistingShortUrl(ALBUM_URL);

        assertThat(firstResult.getStatus()).isEqualTo(ImageHostingShortUrlStatus.RECOVERED_EXISTING);
        assertThat(secondResult.getStatus()).isEqualTo(ImageHostingShortUrlStatus.RECOVERED_EXISTING);
        verify(bitlinksService, times(1)).listBitlinks(GROUP_GUID, 100, null, "both");
    }

    @Test
    void recoverExistingShortUrlReportsLookupMissWhenAlbumUrlIsMissing() {
        ImageHostingShortUrlResult result = service.recoverExistingShortUrl(" ");

        assertThat(result)
                .extracting(
                        ImageHostingShortUrlResult::getStatus,
                        ImageHostingShortUrlResult::getShortUrl,
                        ImageHostingShortUrlResult::getMessage
                )
                .containsExactly(ImageHostingShortUrlStatus.LOOKUP_MISS, null, "Flickr album URL is missing");
        assertThat(result.hasShortUrl()).isFalse();
        verifyNoInteractions(bitlinksService);
    }

    @Test
    void recoverExistingShortUrlReportsLookupMissWhenNoBitlyLinkMatches() {
        when(bitlinksService.listBitlinks(GROUP_GUID, 100, null, "both"))
                .thenReturn(page(List.of(bitlink(
                        "https://www.flickr.com/photos/example/albums/other",
                        "https://bit.ly/other"
                )), null));

        ImageHostingShortUrlResult result = service.recoverExistingShortUrl(ALBUM_URL);

        assertThat(result.getStatus()).isEqualTo(ImageHostingShortUrlStatus.LOOKUP_MISS);
        assertThat(result.getShortUrl()).isNull();
        assertThat(result.getMessage()).isEqualTo("No matching Bitly short URL found");
    }

    @Test
    void recoverExistingShortUrlReportsLookupMissWhenBitlyReturnsNullPage() {
        when(bitlinksService.listBitlinks(GROUP_GUID, 100, null, "both")).thenReturn(null);

        ImageHostingShortUrlResult result = service.recoverExistingShortUrl(ALBUM_URL);

        assertThat(result.getStatus()).isEqualTo(ImageHostingShortUrlStatus.LOOKUP_MISS);
        assertThat(result.getShortUrl()).isNull();
    }

    @Test
    void recoverExistingShortUrlIgnoresBitlinksWithoutUsableUrls() {
        BitlinksPage page = page(List.of(
                bitlink(" ", "https://bit.ly/no-long-url"),
                bitlink(ALBUM_URL, " "),
                bitlink("https://www.example.com/not-flickr", "https://bit.ly/not-flickr")
        ), null);
        page.setPagination(null);
        when(bitlinksService.listBitlinks(GROUP_GUID, 100, null, "both")).thenReturn(page);

        ImageHostingShortUrlResult result = service.recoverExistingShortUrl(ALBUM_URL);

        assertThat(result.getStatus()).isEqualTo(ImageHostingShortUrlStatus.LOOKUP_MISS);
        assertThat(result.getShortUrl()).isNull();
    }

    @Test
    void recoverExistingShortUrlHandlesNullLinksAndBlankPaginationCursor() {
        BitlinksPage page = page(null, " ");
        when(bitlinksService.listBitlinks(GROUP_GUID, 100, null, "both")).thenReturn(page);

        ImageHostingShortUrlResult result = service.recoverExistingShortUrl(ALBUM_URL);

        assertThat(result.getStatus()).isEqualTo(ImageHostingShortUrlStatus.LOOKUP_MISS);
    }

    @Test
    void recoverExistingShortUrlIsDisabledWhenBitlyReadConfigIsMissing() {
        bitlyProperties.setGroupGuid(null);

        ImageHostingShortUrlResult result = service.recoverExistingShortUrl(ALBUM_URL);

        assertThat(result.getStatus()).isEqualTo(ImageHostingShortUrlStatus.DISABLED);
        assertThat(result.getMessage()).isEqualTo("Bitly lookup is disabled or not configured");
        verifyNoInteractions(bitlinksService);
    }

    @Test
    void shortLinksAreOffUnlessTurnedOn() {
        assertThat(new ImageHostingSyncProperties().getShortUrl().isEnabled()).isFalse();
    }

    @Test
    void nothingIsAskedOfBitlyWhenShortLinksAreTurnedOff() {
        // Lower environments: a test album must not spend one of the few links the account may make.
        imageHostingProperties.getShortUrl().setEnabled(false);

        ImageHostingShortUrlResult generated = service.generateShortUrl(ALBUM_URL);
        ImageHostingShortUrlResult recovered = service.recoverExistingShortUrl(ALBUM_URL);

        assertThat(generated.getStatus()).isEqualTo(ImageHostingShortUrlStatus.DISABLED);
        assertThat(generated.getShortUrl()).isNull();
        assertThat(generated.getMessage()).isEqualTo("Short links are turned off in this environment");
        assertThat(recovered.getStatus()).isEqualTo(ImageHostingShortUrlStatus.DISABLED);
        assertThat(recovered.getShortUrl()).isNull();
        verifyNoInteractions(bitlinksService);
    }

    @Test
    void recoverExistingShortUrlIsDisabledWhenBitlyServiceIsMissing() {
        service = new ImageHostingShortUrlService(Optional.empty(), bitlyProperties, imageHostingProperties);

        ImageHostingShortUrlResult result = service.recoverExistingShortUrl(ALBUM_URL);

        assertThat(result.getStatus()).isEqualTo(ImageHostingShortUrlStatus.DISABLED);
        assertThat(result.getShortUrl()).isNull();
    }

    @Test
    void recoverExistingShortUrlReportsFailureWhenBitlyLookupFails() {
        when(bitlinksService.listBitlinks(GROUP_GUID, 100, null, "both"))
                .thenThrow(new IllegalStateException("bitly unavailable"));

        ImageHostingShortUrlResult result = service.recoverExistingShortUrl(ALBUM_URL);

        assertThat(result)
                .extracting(
                        ImageHostingShortUrlResult::getStatus,
                        ImageHostingShortUrlResult::getShortUrl,
                        ImageHostingShortUrlResult::getMessage
                )
                .containsExactly(ImageHostingShortUrlStatus.FAILED, null, "bitly unavailable");
    }

    @Test
    void generateShortUrlReportsLookupMissWhenAlbumUrlIsMissing() {
        ImageHostingShortUrlResult result = service.generateShortUrl(null);

        assertThat(result)
                .extracting(
                        ImageHostingShortUrlResult::getStatus,
                        ImageHostingShortUrlResult::getShortUrl,
                        ImageHostingShortUrlResult::getMessage
                )
                .containsExactly(ImageHostingShortUrlStatus.LOOKUP_MISS, null, "Flickr album URL is missing");
        verifyNoInteractions(bitlinksService);
    }

    @Test
    void generateShortUrlIsDisabledWhenBitlyWriteConfigIsMissing() {
        bitlyProperties.setAccessToken(null);

        ImageHostingShortUrlResult result = service.generateShortUrl(ALBUM_URL);

        assertThat(result.getStatus()).isEqualTo(ImageHostingShortUrlStatus.DISABLED);
        assertThat(result.getMessage()).isEqualTo("Bitly shortening is disabled or not configured");
        verifyNoInteractions(bitlinksService);
    }

    @Test
    void generateShortUrlCallsBitlyShortenWithConfiguredGroup() {
        Bitlink bitlink = new Bitlink();
        bitlink.setLink("https://bit.ly/new-album");
        when(bitlinksService.shorten(any())).thenReturn(bitlink);

        ImageHostingShortUrlResult result = service.generateShortUrl(
                "https://www.flickr.com/photos/example/albums/721577200"
        );

        assertThat(result)
                .extracting(
                        ImageHostingShortUrlResult::getStatus,
                        ImageHostingShortUrlResult::getShortUrl
                )
                .containsExactly(ImageHostingShortUrlStatus.GENERATED_NEW, "https://bit.ly/new-album");
        ArgumentCaptor<ShortenRequest> requestCaptor = ArgumentCaptor.forClass(ShortenRequest.class);
        verify(bitlinksService).shorten(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .extracting(ShortenRequest::getGroupGuid, ShortenRequest::getLongUrl)
                .containsExactly(GROUP_GUID, "https://www.flickr.com/photos/example/albums/721577200");
    }

    @Test
    void generateShortUrlDoesNotSetGroupGuidWhenNotConfigured() {
        bitlyProperties.setGroupGuid(null);
        Bitlink bitlink = new Bitlink();
        bitlink.setLink("https://bit.ly/new-album");
        when(bitlinksService.shorten(any())).thenReturn(bitlink);

        ImageHostingShortUrlResult result = service.generateShortUrl(ALBUM_URL);

        assertThat(result.getStatus()).isEqualTo(ImageHostingShortUrlStatus.GENERATED_NEW);
        ArgumentCaptor<ShortenRequest> requestCaptor = ArgumentCaptor.forClass(ShortenRequest.class);
        verify(bitlinksService).shorten(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getGroupGuid()).isNull();
        assertThat(requestCaptor.getValue().getLongUrl()).isEqualTo(ALBUM_URL);
    }

    @Test
    void generateShortUrlReportsFailureWhenBitlyReturnsNoShortUrl() {
        when(bitlinksService.shorten(any())).thenReturn(new Bitlink());

        ImageHostingShortUrlResult result = service.generateShortUrl(ALBUM_URL);

        assertThat(result)
                .extracting(
                        ImageHostingShortUrlResult::getStatus,
                        ImageHostingShortUrlResult::getShortUrl,
                        ImageHostingShortUrlResult::getMessage
                )
                .containsExactly(ImageHostingShortUrlStatus.FAILED, null, "Bitly did not return a short URL");
    }

    @Test
    void generateShortUrlReportsFailureWhenBitlyReturnsNull() {
        when(bitlinksService.shorten(any())).thenReturn(null);

        ImageHostingShortUrlResult result = service.generateShortUrl(ALBUM_URL);

        assertThat(result.getStatus()).isEqualTo(ImageHostingShortUrlStatus.FAILED);
        assertThat(result.getMessage()).isEqualTo("Bitly did not return a short URL");
    }

    @Test
    void generateShortUrlReportsFailureWhenBitlyShortenFails() {
        when(bitlinksService.shorten(any())).thenThrow(new IllegalStateException("shorten failed"));

        ImageHostingShortUrlResult result = service.generateShortUrl(ALBUM_URL);

        assertThat(result)
                .extracting(
                        ImageHostingShortUrlResult::getStatus,
                        ImageHostingShortUrlResult::getShortUrl,
                        ImageHostingShortUrlResult::getMessage
                )
                .containsExactly(ImageHostingShortUrlStatus.FAILED, null, "shorten failed");
    }

    @Test
    void generateShortUrlCachesGeneratedLinkWhenLookupWasAlreadyLoaded() {
        String generatedAlbumUrl = "https://www.flickr.com/photos/example/albums/721577200";
        String generatedShortUrl = "https://bit.ly/generated";
        when(bitlinksService.listBitlinks(GROUP_GUID, 100, null, "both"))
                .thenReturn(page(List.of(bitlink(ALBUM_URL, SHORT_URL)), null));
        Bitlink generatedBitlink = new Bitlink();
        generatedBitlink.setLink(generatedShortUrl);
        when(bitlinksService.shorten(any())).thenReturn(generatedBitlink);

        service.recoverExistingShortUrl(ALBUM_URL);
        ImageHostingShortUrlResult generateResult = service.generateShortUrl(generatedAlbumUrl);
        ImageHostingShortUrlResult recoverGeneratedResult = service.recoverExistingShortUrl(generatedAlbumUrl);

        assertThat(generateResult.getStatus()).isEqualTo(ImageHostingShortUrlStatus.GENERATED_NEW);
        assertThat(recoverGeneratedResult)
                .extracting(
                        ImageHostingShortUrlResult::getStatus,
                        ImageHostingShortUrlResult::getShortUrl
                )
                .containsExactly(ImageHostingShortUrlStatus.RECOVERED_EXISTING, generatedShortUrl);
        verify(bitlinksService, times(1)).listBitlinks(GROUP_GUID, 100, null, "both");
        verify(bitlinksService, times(1)).shorten(any());
        verifyNoMoreInteractions(bitlinksService);
    }

    private static BitlinksPage page(List<Bitlink> links, String searchAfter) {
        Pagination pagination = new Pagination();
        pagination.setSearchAfter(searchAfter);
        BitlinksPage page = new BitlinksPage();
        page.setLinks(links);
        page.setPagination(pagination);
        return page;
    }

    private static Bitlink bitlink(String longUrl, String link) {
        Bitlink bitlink = new Bitlink();
        bitlink.setLongUrl(longUrl);
        bitlink.setLink(link);
        return bitlink;
    }

}
