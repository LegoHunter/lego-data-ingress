package io.legohunter.egress.imagehosting.difference;

import io.legohunter.egress.imagehosting.headroom.AccountHeadroom;

import java.time.Instant;
import java.util.List;

/**
 * What one comparison of the database's albums with the image host found. The comparison changes nothing.
 *
 * @param complete                    false when the run stopped early; {@code message} says why and the counts
 *                                    and albums are then empty
 * @param account                     the account against its item limit when the run started
 * @param albumsInDatabase            albums the database holds for this image host
 * @param albumsNotPublished          of those, albums not created on the host yet; they are not compared
 * @param albumsChecked               albums compared with the host
 * @param albumsReadInFull            of those, albums whose photo list was read; the rest were compared from
 *                                    the host's album listing alone
 * @param albumsWithDifferences       albums where the host differs from the database
 * @param albumsWithPendingWorkOnly   albums whose only findings are work the sync job has yet to do
 * @param albumsOnHost                albums on the account
 * @param albumsOnHostUnknown         of those, albums the database does not know
 * @param albums                      the albums with a finding
 */
public record HostedDifferenceReport(
        String environment,
        String provider,
        Instant startedAt,
        long elapsedMillis,
        boolean complete,
        String message,
        AccountHeadroom account,
        int albumsInDatabase,
        int albumsNotPublished,
        int albumsChecked,
        int albumsReadInFull,
        int albumsWithDifferences,
        int albumsWithPendingWorkOnly,
        int albumsOnHost,
        int albumsOnHostUnknown,
        List<AlbumFindings> albums
) {
    /** {@code pendingWorkOnly} true means every finding is work the sync job has yet to do. */
    public record AlbumFindings(
            Integer itemInventoryId,
            String itemUuid,
            String hostedAlbumId,
            String albumUrl,
            boolean pendingWorkOnly,
            List<Finding> findings
    ) {
    }

    /**
     * @param hostedPhotoIds    the host's ids of the photos the finding is about; empty when it is about the album
     * @param accountLimitLikely true when the account is limited and at or near its limit, so the host deleting
     *                          its oldest items is the likely cause
     */
    public record Finding(
            HostedDifferenceType type,
            String detail,
            List<String> hostedPhotoIds,
            boolean accountLimitLikely,
            String whatToDo
    ) {
    }
}
