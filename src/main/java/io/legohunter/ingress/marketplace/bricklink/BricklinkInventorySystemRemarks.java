package io.legohunter.ingress.marketplace.bricklink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

final class BricklinkInventorySystemRemarks {
    static final String BEGIN = "[SYSTEM_BEGIN]";
    static final String END = "[SYSTEM_END]";
    static final String MANAGED_KEY = "LEGOHUNTER_MANAGED";
    static final String ENV_KEY = "LEGOHUNTER_ENV";
    static final String MARKETPLACE_LISTING_ID_KEY = "MARKETPLACE_LISTING_ID";
    static final String ITEM_INVENTORY_UUID_KEY = "ITEM_INVENTORY_UUID";

    private BricklinkInventorySystemRemarks() {
    }

    static Optional<SystemBlock> parse(String remarks) {
        if (remarks == null || remarks.isBlank()) {
            return Optional.empty();
        }
        int begin = remarks.indexOf(BEGIN);
        int end = remarks.indexOf(END);
        if (begin < 0 || end < 0 || end < begin) {
            return Optional.empty();
        }
        if (remarks.indexOf(BEGIN, begin + BEGIN.length()) >= 0 || remarks.indexOf(END, end + END.length()) >= 0) {
            return Optional.empty();
        }
        String block = remarks.substring(begin + BEGIN.length(), end).trim();
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : block.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                return Optional.empty();
            }
            values.put(trimmed.substring(0, equals).trim().toUpperCase(), trimmed.substring(equals + 1).trim());
        }
        return Optional.of(new SystemBlock(values, begin, end + END.length()));
    }

    static String systemBlock(String environmentCode, Integer marketplaceListingId, String itemInventoryUuid) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(MANAGED_KEY, "true");
        values.put(ENV_KEY, clean(environmentCode));
        values.put(MARKETPLACE_LISTING_ID_KEY, marketplaceListingId == null ? "" : marketplaceListingId.toString());
        values.put(ITEM_INVENTORY_UUID_KEY, itemInventoryUuid == null ? "" : itemInventoryUuid);
        return BEGIN + " " + values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; ")) + " " + END;
    }

    static String merge(String remarks, String systemBlock) {
        String humanRemarks = parse(remarks)
                .map(block -> (remarks.substring(0, block.beginIndex()) + remarks.substring(block.endIndex())).trim())
                .orElse(remarks == null ? "" : remarks.trim());
        if (humanRemarks.isBlank()) {
            return systemBlock;
        }
        return humanRemarks + " " + systemBlock;
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    record SystemBlock(Map<String, String> values, int beginIndex, int endIndex) {
        String value(String key) {
            return values.get(key);
        }
    }
}
