package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.data.bricklink.BricklinkLotText;
import io.legohunter.data.dao.BricklinkLotTextSource;
import io.legohunter.data.dto.MarketplaceListing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Objects;

/**
 * The text a listing's BrickLink lot shows as its Description, and how to tell whether a lot already shows it.
 * One rule for creating a lot, updating it (HOMELAB-80) and checking it in reconciliation, so they cannot drift.
 *
 * <p>The rule itself is {@link BricklinkLotText} in lego-data (HOMELAB-82): the listing's description or title,
 * a link to the item's photo album, and the "See Extended Description" sentence when a photo has a caption and
 * this environment writes the Extended Description.
 */
@Component
@RequiredArgsConstructor
public class BricklinkLotDescription {
    private final BricklinkLotTextSource lotTextSource;
    private final BricklinkExtendedDescriptionSyncProperties extendedDescriptionSyncProperties;
    private final BricklinkMarketplaceSyncProperties marketplaceSyncProperties;

    /** What the lot should show now; {@link BricklinkLotText.Description#NONE} when the listing has no text at all. */
    public BricklinkLotText.Description expected(MarketplaceListing listing) {
        return lotTextSource.description(listing, writesExtendedDescription());
    }

    /** Whether the lot Description may point buyers at the Extended Description in this environment. */
    boolean writesExtendedDescription() {
        return extendedDescriptionSyncProperties.writes(marketplaceSyncProperties.effectiveMode());
    }

    /**
     * Whether the lot's description already shows {@code expected}. BrickLink stores the text HTML-escaped (a title
     * like "Metro Park &amp; Service Tower"), so both sides are unescaped and trimmed; null and blank are the same.
     * The album link's tag is kept as sent, so it compares as it is.
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
