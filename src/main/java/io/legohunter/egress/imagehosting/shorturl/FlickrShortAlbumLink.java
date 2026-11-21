package io.legohunter.egress.imagehosting.shorturl;

import java.util.Optional;

/**
 * Flickr's own short link for an album (HOMELAB-120): {@code https://flic.kr/s/} followed by the album id in
 * base58, with the alphabet Flickr documents for photo short links ({@code flic.kr/p/}). No API call or credential
 * is needed, and the link works for every existing album.
 *
 * <p>The album form is not in Flickr's API documentation. It was verified on 2026-11-20: Flickr's Share button
 * shows {@code https://flic.kr/s/aHBqjDaRCA} for album 72177720336783526, and the next code opens album
 * 72177720336783527. {@link ShortAlbumLinkCheck} watches for Flickr ever changing it.
 */
public final class FlickrShortAlbumLink {
    public static final String PREFIX = "https://flic.kr/s/";

    private static final String ALPHABET = "123456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length();

    private FlickrShortAlbumLink() {
    }

    /**
     * @param albumId Flickr's album (photoset) id, all digits
     * @return the short link, or empty when the id is missing or not a positive number
     */
    public static Optional<String> of(String albumId) {
        if (albumId == null || albumId.isBlank()) {
            return Optional.empty();
        }
        long id;
        try {
            id = Long.parseLong(albumId.strip());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (id <= 0) {
            return Optional.empty();
        }
        return Optional.of(PREFIX + base58(id));
    }

    static String base58(long value) {
        StringBuilder code = new StringBuilder();
        long remaining = value;
        while (remaining > 0) {
            code.append(ALPHABET.charAt((int) (remaining % BASE)));
            remaining /= BASE;
        }
        return code.reverse().toString();
    }
}
