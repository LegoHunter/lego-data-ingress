package io.legohunter.egress.imagehosting.removal;

import java.util.List;

/**
 * What removing every photo of an item involves. With {@code applied} false nothing was changed and the lists
 * say what would be removed.
 */
public record ItemPurgeReport(
        Integer itemInventoryId,
        String environment,
        boolean applied,
        String hostedAlbumId,
        String hostedAlbumUrl,
        List<PurgedPhoto> photos
) {
    public record PurgedPhoto(
            Integer itemInventoryPhotoId,
            String fileName,
            List<String> hostedPhotoIds,
            String bucket,
            String key
    ) {
    }
}
