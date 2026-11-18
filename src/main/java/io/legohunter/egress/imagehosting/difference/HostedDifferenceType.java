package io.legohunter.egress.imagehosting.difference;

/**
 * One way an album on the image host can differ from what the database says it should be.
 *
 * <p>{@code pending} marks work the database itself already shows as outstanding, which the regular sync job
 * picks up without help. The other types are changes on the host that the database cannot see.
 */
public enum HostedDifferenceType {
    ALBUM_MISSING(false,
            "The album is gone from the image host. Applying the item's sync plan does not recreate it."),
    PHOTOS_DELETED(false,
            "The image host no longer has these photos. Applying the item's sync plan cannot bring them back."),
    PHOTOS_NOT_IN_ALBUM(false,
            "These photos are still on the account but were taken out of the album. Apply the item's sync plan."),
    EXTRA_PHOTOS(false,
            "The album holds photos the database does not list for this item. Look at them, then apply the item's "
                    + "sync plan with allowReviewRequired=true to take them out of the album."),
    PHOTO_ORDER(false,
            "The photos are in a different order on the image host. Apply the item's sync plan."),
    COVER(false,
            "The album's cover is not the item's primary photo. Apply the item's sync plan."),
    TITLE(false,
            "The album title on the image host is not the database's. Apply the item's sync plan."),
    DESCRIPTION(false,
            "The album description on the image host is not the database's. Apply the item's sync plan."),
    PHOTOS_NOT_PUBLISHED(true,
            "These photos have not been published yet. The sync job publishes them; check it if this stays."),
    PHOTO_METADATA_PENDING(true,
            "These photos' title or caption changed and has not been sent yet. The sync job sends it."),
    CHECK_FAILED(false,
            "The album could not be compared. See the detail; the next run tries again.");

    private final boolean pending;
    private final String whatToDo;

    HostedDifferenceType(boolean pending, String whatToDo) {
        this.pending = pending;
        this.whatToDo = whatToDo;
    }

    public boolean pending() {
        return pending;
    }

    public String whatToDo() {
        return whatToDo;
    }
}
