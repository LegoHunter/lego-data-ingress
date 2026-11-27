package io.legohunter.ingress.source.lego.woodentoys.capture;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.legohunter.ingress.source.lego.woodentoys.capture.LegoWoodenToysPageParser.PageFormatException;
import io.legohunter.ingress.source.lego.woodentoys.capture.WoodenToysCaptureFile.Entry;
import io.legohunter.ingress.source.lego.woodentoys.capture.WoodenToysCaptureFile.Picture;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static io.legohunter.ingress.source.lego.woodentoys.capture.CapturePages.entry;
import static io.legohunter.ingress.source.lego.woodentoys.capture.CapturePages.image;
import static io.legohunter.ingress.source.lego.woodentoys.capture.CapturePages.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegoWoodenToysPageParserTest {
    private final LegoWoodenToysPageParser parser = new LegoWoodenToysPageParser(new LegoWoodenToysCaptureProperties());

    @Test
    void readsEveryFieldOfAnEntryAndItsPictures() {
        ObjectNode ferry = entry("blt1", "330-ferry-1957", "330", 8, image("bltA", "ferry_(2).jpg", 2642214));
        ferry.put("product_number_additional_information", "630, 1330");
        ferry.put("original_title", "  Færge ");
        ferry.put("english_title", "Ferry");
        ferry.put("launch_year", "1930'erne (primo)");
        ferry.put("average_size", "30 cm");
        ferry.put("serial_number", "Series 3");
        ferry.put("comment", "Ikke i prislister.");
        ferry.put("english_comment", "Not in pricelists.");

        LegoWoodenToysPageParser.Page page = parser.parse(page(667, List.of(ferry)));

        assertThat(page.reportedCount()).isEqualTo(667);
        assertThat(page.entries()).hasSize(1);
        Entry entry = page.entries().get(0);
        assertThat(entry.uid()).isEqualTo("blt1");
        assertThat(entry.slug()).isEqualTo("330-ferry-1957");
        assertThat(entry.url()).isEqualTo("https://www.lego.com/en-us/history/wooden-toys/330-ferry-1957");
        assertThat(entry.productNumber()).isEqualTo("330");
        assertThat(entry.productNumberSortable()).isEqualTo("0330");
        assertThat(entry.alternateNumberText()).isEqualTo("630, 1330");
        assertThat(entry.originalTitle()).isEqualTo("Færge");
        assertThat(entry.englishTitle()).isEqualTo("Ferry");
        assertThat(entry.launchYearText()).isEqualTo("1930'erne (primo)");
        assertThat(entry.exitYearText()).isEqualTo("N/A");
        assertThat(entry.averageSizeText()).isEqualTo("30 cm");
        assertThat(entry.seriesText()).isEqualTo("Series 3");
        assertThat(entry.notesOriginal()).isEqualTo("Ikke i prislister.");
        assertThat(entry.notesEnglish()).isEqualTo("Not in pricelists.");
        assertThat(entry.sourceVersion()).isEqualTo(8);
        assertThat(entry.sourceUpdatedAt()).isEqualTo(Instant.parse("2024-10-11T04:44:45.547Z"));

        assertThat(entry.pictures()).hasSize(1);
        Picture picture = entry.pictures().get(0);
        assertThat(picture.sortOrder()).isZero();
        assertThat(picture.sourceAssetId()).isEqualTo("bltA");
        assertThat(picture.fileName()).isEqualTo("ferry_(2).jpg");
        assertThat(picture.contentType()).isEqualTo("image/jpeg");
        assertThat(picture.width()).isEqualTo(4272);
        assertThat(picture.height()).isEqualTo(2848);
        assertThat(picture.byteSize()).isEqualTo(2642214L);
        assertThat(picture.sourceUrl()).isEqualTo("https://www.lego.com/cdn/cs/history/assets/bltA/ferry_(2).jpg");
        assertThat(picture.objectKey()).isNull();
        assertThat(picture.alreadyHeld()).isFalse();
    }

    @Test
    void anEmptyFieldIsLeftOutAndPicturesKeepTheirOrder() {
        ObjectNode toy = entry("blt1", "a-toy", "1", 1, image("bltA", "a.jpg", 1), image("bltB", "b.jpg", 2));

        Entry entry = parser.parse(page(1, List.of(toy))).entries().get(0);

        assertThat(entry.alternateNumberText()).isNull();
        assertThat(entry.averageSizeText()).isNull();
        assertThat(entry.notesEnglish()).isNull();
        assertThat(entry.pictures()).extracting(Picture::sourceAssetId).containsExactly("bltA", "bltB");
        assertThat(entry.pictures()).extracting(Picture::sortOrder).containsExactly(0, 1);
    }

    @Test
    void anEntryWithoutPicturesHasAnEmptyList() {
        ObjectNode toy = entry("blt1", "a-toy", "1", 1);
        toy.remove("images");

        assertThat(parser.parse(page(1, List.of(toy))).entries().get(0).pictures()).isEmpty();
    }

    @Test
    void aPageWithoutTheEntryListIsRefused() {
        assertThatThrownBy(() -> parser.parse("<html>Access denied</html>"))
                .isInstanceOf(PageFormatException.class)
                .hasMessageContaining("no entry list");
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(PageFormatException.class);
    }

    @Test
    void anEntryListCutShortIsRefused() {
        String whole = page(1, List.of(entry("blt1", "a-toy", "1", 1)));
        String cut = whole.substring(0, whole.indexOf("\"product_number\""));

        assertThatThrownBy(() -> parser.parse(cut)).isInstanceOf(PageFormatException.class);
    }

    @Test
    void anEntryMissingItsIdAddressOrProductNumberIsRefused() {
        for (String field : List.of("uid", "url", "product_number")) {
            ObjectNode toy = entry("blt1", "a-toy", "1", 1);
            toy.remove(field);
            assertThatThrownBy(() -> parser.parse(page(1, List.of(toy))))
                    .as(field)
                    .isInstanceOf(PageFormatException.class)
                    .hasMessageContaining("no " + field);
        }
    }

    @Test
    void anEntryWithAnAddressOutsideTheCatalogIsRefused() {
        ObjectNode toy = entry("blt1", "a-toy", "1", 1);
        toy.put("url", "/history/somewhere-else");

        assertThatThrownBy(() -> parser.parse(page(1, List.of(toy))))
                .isInstanceOf(PageFormatException.class)
                .hasMessageContaining("unexpected address");
    }
}
