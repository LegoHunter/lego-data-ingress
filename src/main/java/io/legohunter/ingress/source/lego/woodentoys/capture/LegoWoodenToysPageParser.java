package io.legohunter.ingress.source.lego.woodentoys.capture;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the catalog entries out of one overview page of lego.com's wooden toys catalog.
 *
 * <p>The page is requested as page data (request header {@code RSC: 1}), which is text with the page's records
 * embedded as JSON after the name {@code "initialList"}: {@code {"Results":[...],"count":667}}. Nothing else of the
 * page is read. Anything unexpected is a {@link PageFormatException}, so a changed page stops the capture instead of
 * producing a short or empty catalog.
 */
public class LegoWoodenToysPageParser {
    private static final String LIST_NAME = "\"initialList\":";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LegoWoodenToysCaptureProperties properties;

    public LegoWoodenToysPageParser(LegoWoodenToysCaptureProperties properties) {
        this.properties = properties;
    }

    /** One page: the catalog size the site reports, and the page's entries with every listed picture. */
    public record Page(int reportedCount, List<WoodenToysCaptureFile.Entry> entries) {
    }

    public static class PageFormatException extends RuntimeException {
        public PageFormatException(String message) {
            super(message);
        }

        public PageFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Page parse(String pageData) {
        int at = pageData == null ? -1 : pageData.indexOf(LIST_NAME);
        if (at < 0) {
            throw new PageFormatException("the page holds no entry list");
        }
        JsonNode list;
        try (JsonParser parser = objectMapper.getFactory().createParser(pageData.substring(at + LIST_NAME.length()))) {
            list = objectMapper.readTree(parser);
        } catch (IOException e) {
            throw new PageFormatException("the page's entry list is not readable", e);
        }
        JsonNode results = list == null ? null : list.get("Results");
        JsonNode count = list == null ? null : list.get("count");
        if (results == null || !results.isArray() || count == null || !count.canConvertToInt()) {
            throw new PageFormatException("the page's entry list has no Results or no count");
        }
        List<WoodenToysCaptureFile.Entry> entries = new ArrayList<>();
        for (JsonNode result : results) {
            entries.add(entry(result));
        }
        return new Page(count.asInt(), entries);
    }

    private WoodenToysCaptureFile.Entry entry(JsonNode node) {
        String uid = required(node, "uid", "an entry");
        String path = required(node, "url", "entry " + uid);
        String slugPrefix = properties.getEntrySlugPrefix();
        if (!path.startsWith(slugPrefix) || path.length() == slugPrefix.length()) {
            throw new PageFormatException("entry " + uid + " has an unexpected address: " + path);
        }
        String productNumber = required(node, "product_number", "entry " + uid);

        List<WoodenToysCaptureFile.Picture> pictures = new ArrayList<>();
        JsonNode images = node.get("images");
        if (images != null && !images.isNull()) {
            if (!images.isArray()) {
                throw new PageFormatException("entry " + uid + " has an unreadable picture list");
            }
            int position = 0;
            for (JsonNode image : images) {
                pictures.add(picture(image, position++, uid));
            }
        }
        return new WoodenToysCaptureFile.Entry(
                uid,
                path.substring(slugPrefix.length()),
                properties.entryUrl(path),
                productNumber,
                text(node, "product_number_sortable"),
                text(node, "product_number_additional_information"),
                text(node, "original_title"),
                text(node, "english_title"),
                text(node, "launch_year"),
                text(node, "exit_year"),
                text(node, "average_size"),
                text(node, "serial_number"),
                text(node, "comment"),
                text(node, "english_comment"),
                node.path("_version").canConvertToInt() ? node.path("_version").asInt() : null,
                instant(node, "updated_at", uid),
                pictures
        );
    }

    private WoodenToysCaptureFile.Picture picture(JsonNode node, int position, String entryUid) {
        String where = "a picture of entry " + entryUid;
        String assetId = required(node, "uid", where);
        JsonNode dimension = node.path("dimension");
        return new WoodenToysCaptureFile.Picture(
                position,
                assetId,
                required(node, "filename", where),
                text(node, "content_type"),
                dimension.path("width").canConvertToInt() ? dimension.path("width").asInt() : null,
                dimension.path("height").canConvertToInt() ? dimension.path("height").asInt() : null,
                size(node.path("file_size")),
                null,
                required(node, "url", where),
                null,
                false
        );
    }

    /** LEGO states the size as text, for example {@code "2642214"}. */
    private static Long size(JsonNode node) {
        if (node.isNumber()) {
            return node.asLong();
        }
        try {
            return node.isTextual() && !node.asText().isBlank() ? Long.valueOf(node.asText().strip()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String required(JsonNode node, String field, String where) {
        String value = text(node, field);
        if (value == null) {
            throw new PageFormatException(where + " has no " + field);
        }
        return value;
    }

    /** The field as trimmed text, or null when it is missing or empty. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }

    private static Instant instant(JsonNode node, String field, String uid) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new PageFormatException("entry " + uid + " has an unreadable " + field + ": " + value, e);
        }
    }
}
