package io.legohunter.ingress.source.lego.woodentoys.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Builds page data shaped like lego.com's, with made-up entries. */
final class CapturePages {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String STOCK_ID = "blt360cf3fd32e46eb8";

    private CapturePages() {
    }

    /** Page data: the entry list between other page content, as the site sends it. */
    static String page(int count, List<ObjectNode> entries) {
        ObjectNode list = MAPPER.createObjectNode();
        ArrayNode results = list.putArray("Results");
        entries.forEach(results::add);
        list.put("count", count);
        return "1:\"$Sreact.fragment\"\n5:[\"$\",\"div\",null,{\"filters\":{\"exitYears\":[1932,1933]},"
                + "\"locale\":\"en-us\",\"initialList\":" + list + ",\"labels\":{\"next\":\"Next\"}}]\n6:null\n";
    }

    static ObjectNode entry(String uid, String slug, String productNumber, int version, ObjectNode... images) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("uid", uid);
        entry.put("_version", version);
        entry.put("locale", "en-us");
        entry.put("url", "/wooden-toys/" + slug);
        entry.put("product_number", productNumber);
        entry.put("product_number_sortable", "0" + productNumber);
        entry.put("product_number_additional_information", "");
        entry.put("original_title", "Titel " + uid + " ");
        entry.put("english_title", " Title " + uid);
        entry.put("launch_year", "1957");
        entry.put("exit_year", "N/A");
        entry.put("average_size", "");
        entry.put("serial_number", "");
        entry.put("comment", "");
        entry.put("english_comment", "");
        entry.put("updated_at", "2024-10-11T04:44:45.547Z");
        ArrayNode list = entry.putArray("images");
        for (ObjectNode image : images) {
            list.add(image);
        }
        return entry;
    }

    static ObjectNode image(String assetId, String fileName, long size) {
        ObjectNode image = MAPPER.createObjectNode();
        image.put("uid", assetId);
        image.put("filename", fileName);
        image.put("content_type", "image/jpeg");
        image.put("file_size", Long.toString(size));
        image.putObject("dimension").put("height", 2848).put("width", 4272);
        image.put("url", url(assetId, fileName));
        return image;
    }

    static ObjectNode stockImage() {
        return image(STOCK_ID, "98765_image_on_its_way.jpg", 428828);
    }

    static String url(String assetId, String fileName) {
        return "https://www.lego.com/cdn/cs/history/assets/" + assetId + "/" + fileName;
    }
}
