package io.legohunter.ingress.source.lego.woodentoys.capture;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** What the database already holds of LEGO's wooden toys catalog. Read-only: the capture never writes to it. */
public interface LegoWoodenToysCatalogState {

    /** The number of stored entries that came from lego.com (owner-made entries are not counted). */
    int capturedEntryCount();

    /** The stored entry with LEGO's id {@code uid}, or else the one with the address {@code slug}. */
    Optional<HeldEntry> find(String uid, String slug);

    /**
     * A stored entry.
     *
     * @param sourceVersion       LEGO's version number when it was stored
     * @param sourceUpdatedAt     LEGO's update time when it was stored
     * @param legoPictureAssetIds LEGO's ids of the entry's stored pictures that came from lego.com
     */
    record HeldEntry(Integer sourceVersion, Instant sourceUpdatedAt, Set<String> legoPictureAssetIds) {
    }
}
