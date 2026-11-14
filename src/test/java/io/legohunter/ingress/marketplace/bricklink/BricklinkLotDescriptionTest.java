package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.data.dto.MarketplaceListing;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkLotDescriptionTest {

    @Test
    void theListingDescriptionWinsAndTheTitleIsTheFallback() {
        assertThat(BricklinkLotDescription.expected(listing(" Main Street ", " Complete with box "))).isEqualTo("Complete with box");
        assertThat(BricklinkLotDescription.expected(listing(" Main Street ", "  "))).isEqualTo("Main Street");
        assertThat(BricklinkLotDescription.expected(listing(null, null))).isNull();
        assertThat(BricklinkLotDescription.expected(listing("  ", ""))).isNull();
        assertThat(BricklinkLotDescription.expected(null)).isNull();
    }

    @Test
    void matchingIgnoresHtmlEscapingSurroundingSpaceAndEmptyVersusNull() {
        assertThat(BricklinkLotDescription.matches("Metro Park & Service Tower", "Metro Park &amp; Service Tower")).isTrue();
        assertThat(BricklinkLotDescription.matches("Main Street", " Main Street ")).isTrue();
        assertThat(BricklinkLotDescription.matches(null, "")).isTrue();
        assertThat(BricklinkLotDescription.matches("Main Street", "")).isFalse();
        assertThat(BricklinkLotDescription.matches("Main Street", "Main  Street")).isFalse();
    }

    private static MarketplaceListing listing(String title, String description) {
        return MarketplaceListing.builder().title(title).description(description).build();
    }
}
