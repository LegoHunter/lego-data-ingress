package io.legohunter.ingress.pricing;

final class BricklinkPricingCodeNormalizer {
    private BricklinkPricingCodeNormalizer() {
    }

    static String condition(String value) {
        String normalized = cleanUpper(value);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "NEW", "N" -> "N";
            case "USED", "U" -> "U";
            default -> null;
        };
    }

    static String completeness(String value) {
        String normalized = cleanUpper(value);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "SEALED", "S" -> "S";
            case "COMPLETE", "C" -> "C";
            case "INCOMPLETE", "I", "X" -> "X";
            default -> normalized;
        };
    }

    private static String cleanUpper(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase();
    }
}
