package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.data.dto.MarketplaceListing;
import org.springframework.web.util.HtmlUtils;

import java.util.Objects;

/**
 * The text a listing's BrickLink lot shows as its Description, and how to tell whether a lot already shows it.
 * One rule for creating a lot, updating it (HOMELAB-80) and checking it in reconciliation, so they cannot drift:
 * the listing's description when it has one, otherwise its title.
 */
final class BricklinkLotDescription {
    private BricklinkLotDescription() {
    }

    /** What the lot should show, or null when the listing has neither a description nor a title. */
    static String expected(MarketplaceListing listing) {
        if (listing == null) {
            return null;
        }
        if (listing.getDescription() != null && !listing.getDescription().isBlank()) {
            return listing.getDescription().trim();
        }
        return listing.getTitle() == null || listing.getTitle().isBlank() ? null : listing.getTitle().trim();
    }

    /**
     * Whether the lot's description already shows {@code expected}. BrickLink may return text HTML-escaped (a title
     * like "Metro Park &amp; Service Tower"), so both sides are unescaped and trimmed; null and blank are the same.
     */
    static boolean matches(String expected, String remote) {
        return Objects.equals(normalize(expected), normalize(remote));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return HtmlUtils.htmlUnescape(value).trim();
    }
}
