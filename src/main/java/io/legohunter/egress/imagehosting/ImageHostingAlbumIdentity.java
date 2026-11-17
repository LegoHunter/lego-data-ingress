package io.legohunter.egress.imagehosting;

import java.util.Locale;

/**
 * How a hosted album is tied to one inventory item (HOMELAB-105).
 *
 * <p>An album's title is the item number and name, so every copy of the same set has the same title; the title
 * cannot say which copy an album belongs to. The item's UUID can. Every album description the system writes ends
 * with a reference line carrying the UUID, and an existing album is recognised as an item's album only when its
 * description carries that UUID. Albums made by the legacy system have the bare UUID as their whole description,
 * which this also recognises.
 */
public final class ImageHostingAlbumIdentity {
    static final String REFERENCE_LABEL = "Item reference: ";

    private ImageHostingAlbumIdentity() {
    }

    /** The description with the item's reference line appended, unless it already carries the UUID. */
    public static String withItemReference(String description, String uuid) {
        if (isBlank(uuid) || carries(description, uuid)) {
            return description;
        }
        String reference = REFERENCE_LABEL + uuid.trim();
        return isBlank(description) ? reference : description.stripTrailing() + "\n\n" + reference;
    }

    /**
     * True when the description contains the UUID as a whole word: not as part of a longer run of letters, digits
     * or hyphens. Case is ignored.
     */
    public static boolean carries(String description, String uuid) {
        if (isBlank(description) || isBlank(uuid)) {
            return false;
        }
        String text = description.toLowerCase(Locale.ROOT);
        String wanted = uuid.trim().toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int at = text.indexOf(wanted, from);
            if (at < 0) {
                return false;
            }
            int end = at + wanted.length();
            boolean startsClean = at == 0 || !isUuidCharacter(text.charAt(at - 1));
            boolean endsClean = end == text.length() || !isUuidCharacter(text.charAt(end));
            if (startsClean && endsClean) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isUuidCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '-';
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
