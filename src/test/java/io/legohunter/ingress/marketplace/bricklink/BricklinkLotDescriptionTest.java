package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.data.dao.BricklinkLotTextSource;
import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.MarketplaceListing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BricklinkLotDescriptionTest {
    private static final String NOTE = "See Extended Description for additional Item Condition notes.";

    private final ExternalImageAlbumDao albums = mock(ExternalImageAlbumDao.class);
    private final ItemInventoryPhotoDao photos = mock(ItemInventoryPhotoDao.class);
    private final BricklinkExtendedDescriptionSyncProperties extendedDescriptionSync = new BricklinkExtendedDescriptionSyncProperties();
    private final BricklinkMarketplaceSyncProperties marketplaceSync = new BricklinkMarketplaceSyncProperties();
    private final BricklinkLotDescription lotDescription = new BricklinkLotDescription(
            new BricklinkLotTextSource(albums, photos), extendedDescriptionSync, marketplaceSync);

    @Test
    void theListingDescriptionWinsAndTheTitleIsTheFallback() {
        assertThat(lotDescription.expected(listing(" Main Street ", " Complete with box ")).text()).isEqualTo("Complete with box");
        assertThat(lotDescription.expected(listing(" Main Street ", "  ")).text()).isEqualTo("Main Street");
        assertThat(lotDescription.expected(listing(null, null)).present()).isFalse();
        assertThat(lotDescription.expected(listing("  ", "")).present()).isFalse();
        assertThat(lotDescription.expected(null).present()).isFalse();
    }

    @Test
    void theAlbumLinkFollowsTheText() {
        when(albums.findByExternalServiceIdAndItemInventoryId(10, 958)).thenReturn(Optional.of(
                ExternalImageAlbum.builder().albumUrl("https://www.flickr.com/photos/u/sets/1/").build()));

        assertThat(lotDescription.expected(listing("Test Car", null)).text())
                .isEqualTo("Test Car. <a href=\"https://www.flickr.com/photos/u/sets/1/\">View all photos</a>.");
    }

    @Test
    void theExtendedDescriptionNoteAppearsOnlyWhereTheExtendedDescriptionIsWritten() {
        when(photos.findProcessedCaptionsByItemInventoryId(958)).thenReturn(List.of("Small mark."));

        // Sync off, or on in DRY_RUN, or APPLY while marketplace sync is DRY_RUN: no note.
        assertThat(lotDescription.expected(listing("Test Car", null)).text()).isEqualTo("Test Car");
        extendedDescriptionSync.setEnabled(true);
        extendedDescriptionSync.setMode("APPLY");
        assertThat(lotDescription.expected(listing("Test Car", null)).text()).isEqualTo("Test Car");

        marketplaceSync.setEnvironmentCode("sandbox");
        marketplaceSync.setMode("APPLY");
        assertThat(lotDescription.expected(listing("Test Car", null)).text()).isEqualTo("Test Car. " + NOTE);
    }

    @Test
    void matchingIgnoresHtmlEscapingSurroundingSpaceAndEmptyVersusNull() {
        assertThat(BricklinkLotDescription.matches("Metro Park & Service Tower", "Metro Park &amp; Service Tower")).isTrue();
        assertThat(BricklinkLotDescription.matches("Main Street", " Main Street ")).isTrue();
        assertThat(BricklinkLotDescription.matches(null, "")).isTrue();
        assertThat(BricklinkLotDescription.matches("Main Street", "")).isFalse();
        assertThat(BricklinkLotDescription.matches("Main Street", "Main  Street")).isFalse();
    }

    @Test
    void theAlbumLinkMatchesWhatBricklinkStores() {
        // Measured 2026-11-19 on a SANDBOX lot: the tag comes back as sent, the ampersand escaped.
        assertThat(BricklinkLotDescription.matches(
                "Test & Car. <a href=\"https://www.flickr.com/photos/u/sets/1/\">View all photos</a>.",
                "Test &amp; Car. <a href=\"https://www.flickr.com/photos/u/sets/1/\">View all photos</a>.")).isTrue();
    }

    private static MarketplaceListing listing(String title, String description) {
        return MarketplaceListing.builder().itemInventoryId(958).title(title).description(description).build();
    }
}
