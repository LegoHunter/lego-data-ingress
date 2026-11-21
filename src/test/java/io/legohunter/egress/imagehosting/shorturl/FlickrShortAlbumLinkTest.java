package io.legohunter.egress.imagehosting.shorturl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FlickrShortAlbumLinkTest {

    /** Pairs checked against Flickr on 2026-11-20 (HOMELAB-120): each link opened that album, signed out. */
    @ParameterizedTest
    @CsvSource({
            // SANDBOX album 563 (item 958); Flickr's Share button shows the same link.
            "72177720336783526, https://flic.kr/s/aHBqjDaRCA",
            // A legacy PROD album on the Pro account (6681-1 Police Van).
            "72157709437946461, https://flic.kr/s/aHsmENyGBP",
            // The next id: another member's album, which shows the code is the id itself, with no offset.
            "72177720336783527, https://flic.kr/s/aHBqjDaRCB",
            // Surrounding spaces are ignored.
            "' 72177720336783526 ', https://flic.kr/s/aHBqjDaRCA"
    })
    void ofEncodesTheAlbumIdInBase58(String albumId, String expected) {
        assertThat(FlickrShortAlbumLink.of(albumId)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "album-100", "0", "-5", "99999999999999999999"})
    void ofIsEmptyWithoutAUsableAlbumId(String albumId) {
        assertThat(FlickrShortAlbumLink.of(albumId)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "1, 2",
            "57, Z",
            "58, 21",
            "9223372036854775807, npL6MjP8Qfc"
    })
    void base58UsesFlickrsAlphabet(long value, String expected) {
        assertThat(FlickrShortAlbumLink.base58(value)).isEqualTo(expected);
    }
}
