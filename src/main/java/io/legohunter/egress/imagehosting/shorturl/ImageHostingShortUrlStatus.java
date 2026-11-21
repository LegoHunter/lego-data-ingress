package io.legohunter.egress.imagehosting.shorturl;

public enum ImageHostingShortUrlStatus {
    /** The short link was calculated from the album id. */
    CALCULATED,
    /** No short link: the album id is missing or not a Flickr album id. The full album address is used instead. */
    UNAVAILABLE
}
