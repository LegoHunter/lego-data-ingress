package io.legohunter.egress.imagehosting.shorturl;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.enums.ExternalSyncStatus;
import io.legohunter.ingress.marketplace.bricklink.BricklinkLotDescription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortUrlBackfillServiceTest {
    private static final int FLICKR = 10;
    private static final int BRICKLINK = 2;

    private ExternalImageAlbumDao albumDao;
    private MarketplaceListingDao listingDao;
    private ItemInventoryPhotoDao photoDao;
    private ShortUrlBackfillService service;

    private ExternalImageAlbum bitlyAlbum;
    private ExternalImageAlbum currentAlbum;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        albumDao = mock(ExternalImageAlbumDao.class);
        listingDao = mock(MarketplaceListingDao.class);
        photoDao = mock(ItemInventoryPhotoDao.class);
        BricklinkLotDescription lotDescription = mock(BricklinkLotDescription.class);
        when(lotDescription.writesExtendedDescription()).thenReturn(true);
        ObjectProvider<BricklinkLotDescription> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(lotDescription);
        service = new ShortUrlBackfillService(albumDao, listingDao, photoDao, provider);

        bitlyAlbum = album(563L, 958, "72177720336783526", "https://bit.ly/3XeeF0K");
        currentAlbum = album(564L, 959, "72157709437946461", "https://flic.kr/s/aHsmENyGBP");
        ExternalImageAlbum noLinkYet = album(565L, 960, "72177720336783527", null);
        ExternalImageAlbum notCreatedYet = album(566L, 961, null, null);
        ExternalImageAlbum otherService = album(567L, 962, "123", null);
        otherService.setExternalServiceId(99);
        when(albumDao.findAll()).thenReturn(Set.of(bitlyAlbum, currentAlbum, noLinkYet, notCreatedYet, otherService));

        when(photoDao.findProcessedCaptionsByItemInventoryId(any())).thenReturn(List.of());
        when(photoDao.countProcessedByItemInventoryId(any())).thenReturn(6);
        when(listingDao.findByItemInventoryId(any())).thenReturn(Set.of());
        // Item 958: one BrickLink lot whose text gets too long with the new link, one short lot, one listing
        // with no lot yet and one listing on another marketplace.
        when(listingDao.findByItemInventoryId(958)).thenReturn(Set.of(
                listing(1, BRICKLINK, "301", "x".repeat(210)),
                listing(2, BRICKLINK, "302", "Test Car"),
                listing(3, BRICKLINK, null, "Not listed yet"),
                listing(4, 3, "ebay-1", "On eBay")));
    }

    @Test
    void dryRunReportsTheChangesAndWritesNothing() {
        ShortUrlBackfillService.Result result = service.backfill(false);

        assertThat(result.applied()).isFalse();
        assertThat(result.flickrAlbums()).isEqualTo(4);
        assertThat(result.changed()).isEqualTo(2);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.withoutAlbumId()).isEqualTo(1);
        assertThat(result.rows())
                .extracting(ShortUrlBackfillService.Row::externalImageAlbumId,
                        ShortUrlBackfillService.Row::currentShortUrl,
                        ShortUrlBackfillService.Row::newShortUrl,
                        ShortUrlBackfillService.Row::applied)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(563L, "https://bit.ly/3XeeF0K", "https://flic.kr/s/aHBqjDaRCA", false),
                        org.assertj.core.groups.Tuple.tuple(565L, null, "https://flic.kr/s/aHBqjDaRCB", false));
        verify(albumDao, never()).update(any());
        assertThat(bitlyAlbum.getShortUrl()).isEqualTo("https://bit.ly/3XeeF0K");
        assertThat(bitlyAlbum.getSyncStatus()).isEqualTo(ExternalSyncStatus.SYNCED);
    }

    @Test
    void dryRunListsBricklinkLotsWhoseDescriptionWouldBeTooLong() {
        ShortUrlBackfillService.Result result = service.backfill(false);

        List<ShortUrlBackfillService.Listing> listings = result.rows().getFirst().bricklinkListings();
        assertThat(listings).extracting(ShortUrlBackfillService.Listing::marketplaceListingId).containsExactly(1, 2);
        assertThat(listings.get(0).tooLong()).isTrue();
        assertThat(listings.get(0).newDescriptionLength()).isGreaterThan(255);
        assertThat(listings.get(1).tooLong()).isFalse();
        assertThat(result.bricklinkDescriptionsTooLong()).isEqualTo(1);
    }

    @Test
    void applyUpdatesOnlyTheAlbumsThatChange() {
        ShortUrlBackfillService.Result result = service.backfill(true);

        assertThat(result.applied()).isTrue();
        assertThat(result.rows()).allMatch(ShortUrlBackfillService.Row::applied);
        verify(albumDao).update(bitlyAlbum);
        verify(albumDao, never()).update(currentAlbum);
        assertThat(bitlyAlbum.getShortUrl()).isEqualTo("https://flic.kr/s/aHBqjDaRCA");
        // PENDING makes the scheduled image-hosting sync revisit the item and rewrite the Flickr album description.
        assertThat(bitlyAlbum.getSyncStatus()).isEqualTo(ExternalSyncStatus.PENDING);
        assertThat(currentAlbum.getSyncStatus()).isEqualTo(ExternalSyncStatus.SYNCED);
    }

    private static ExternalImageAlbum album(Long id, Integer itemInventoryId, String albumId, String shortUrl) {
        return ExternalImageAlbum.builder()
                .externalImageAlbumId(id)
                .externalServiceId(FLICKR)
                .itemInventoryId(itemInventoryId)
                .externalAlbumId(albumId)
                .albumUrl(albumId == null ? null : "https://www.flickr.com/photos/u/sets/" + albumId + "/")
                .shortUrl(shortUrl)
                .syncStatus(ExternalSyncStatus.SYNCED)
                .build();
    }

    private static MarketplaceListing listing(Integer id, Integer serviceId, String lotId, String description) {
        return MarketplaceListing.builder()
                .marketplaceListingId(id)
                .itemInventoryId(958)
                .listingExternalServiceId(serviceId)
                .externalListingId(lotId)
                .listingStatusCode("ACTIVE")
                .title("Title")
                .description(description)
                .build();
    }
}
