package io.legohunter.egress.imagehosting;

import java.util.List;

public record ImageHostingScheduledSyncResult(
        String provider,
        Integer externalServiceId,
        String outcome,
        int itemInventoriesDiscovered,
        int itemInventoriesSynced,
        int itemInventoriesFailed,
        long elapsedMillis,
        List<Integer> itemInventoryIds,
        List<Integer> syncedItemInventoryIds,
        List<Integer> failedItemInventoryIds,
        boolean apply,
        ImageHostingScheduledSyncCandidateCounts candidateCounts
) {
    public ImageHostingScheduledSyncResult {
        itemInventoryIds = List.copyOf(itemInventoryIds);
        syncedItemInventoryIds = List.copyOf(syncedItemInventoryIds);
        failedItemInventoryIds = List.copyOf(failedItemInventoryIds);
        candidateCounts = candidateCounts == null
                ? new ImageHostingScheduledSyncCandidateCounts(0, 0, 0, 0, 0, 0, 0)
                : candidateCounts;
    }
}
