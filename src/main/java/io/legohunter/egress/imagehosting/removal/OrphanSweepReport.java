package io.legohunter.egress.imagehosting.removal;

import java.util.List;

/**
 * What the image host account holds that no inventory item owns. With {@code applied} false nothing was
 * deleted.
 *
 * @param orphanPhotos   photos on the account that the database does not know
 * @param unknownAlbums  albums on the account that the database does not know; they disappear from the host
 *                       when their last photo is deleted
 * @param deletedPhotoIds photos deleted in this run
 * @param failures       photos that could not be deleted, with the host's reason
 * @param remaining      orphan photos left because the run reached {@code maxDeletes}
 */
public record OrphanSweepReport(
        String environment,
        boolean applied,
        int albumsSeen,
        int photosSeen,
        List<OrphanPhoto> orphanPhotos,
        List<UnknownAlbum> unknownAlbums,
        List<String> deletedPhotoIds,
        List<String> failures,
        int remaining
) {
    public record OrphanPhoto(String hostedPhotoId, String title, String hostedAlbumId) {
    }

    /** {@code knownPhotos} above zero means the album holds photos an item does own: check it by hand. */
    public record UnknownAlbum(String hostedAlbumId, String title, int photos, int knownPhotos) {
    }
}
