package io.legohunter.egress.imagehosting;

import io.legohunter.data.dao.ImageHostingSyncCandidate;
import io.legohunter.data.dao.ImageHostingSyncCandidateReason;

import java.util.List;

public record ImageHostingScheduledSyncCandidateCounts(
        int missingAlbumLink,
        int missingPhotoLink,
        int missingAlbumMembership,
        int failedSync,
        int pendingSync,
        int metadataChanged,
        int contentChanged
) {
    public static ImageHostingScheduledSyncCandidateCounts from(List<ImageHostingSyncCandidate> candidates) {
        return new ImageHostingScheduledSyncCandidateCounts(
                count(candidates, ImageHostingSyncCandidateReason.MISSING_ALBUM_LINK),
                count(candidates, ImageHostingSyncCandidateReason.MISSING_PHOTO_LINK),
                count(candidates, ImageHostingSyncCandidateReason.MISSING_ALBUM_MEMBERSHIP),
                count(candidates, ImageHostingSyncCandidateReason.FAILED_SYNC),
                count(candidates, ImageHostingSyncCandidateReason.PENDING_SYNC),
                count(candidates, ImageHostingSyncCandidateReason.METADATA_CHANGED),
                count(candidates, ImageHostingSyncCandidateReason.CONTENT_CHANGED)
        );
    }

    private static int count(List<ImageHostingSyncCandidate> candidates, ImageHostingSyncCandidateReason reason) {
        return (int) candidates.stream()
                .filter(candidate -> candidate.reasons().contains(reason))
                .count();
    }
}
