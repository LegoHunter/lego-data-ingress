package io.legohunter.egress.imagehosting;

import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dao.ImageHostingSyncCandidate;
import io.legohunter.egress.imagehosting.plan.ImageHostingSyncPlanExecutor;
import io.legohunter.egress.imagehosting.plan.ImageHostingSyncPlanRequest;
import io.legohunter.egress.imagehosting.plan.ImageHostingSyncPlanService;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.imaging.service.sync.model.SyncReport;
import io.legohunter.imaging.service.sync.model.SyncReportSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lego.image-hosting.sync.scheduled", name = "enabled", havingValue = "true")
public class ImageHostingScheduledSyncJob {
    private final ExternalImageDao externalImageDao;
    private final ImageHostingSyncPlanService syncPlanService;
    private final ImageHostingSyncPlanExecutor syncPlanExecutor;
    private final ImageHostingSyncProperties properties;
    private final ImageHostingSyncMetricsService metricsService;

    @Scheduled(
            fixedDelayString = "${lego.image-hosting.sync.scheduled.fixed-delay-ms:300000}",
            initialDelayString = "${lego.image-hosting.sync.scheduled.initial-delay-ms:30000}"
    )
    @SchedulerLock(
            name = "ImageHostingScheduledSyncJob",
            lockAtMostFor = "${lego.image-hosting.sync.scheduled.lock-at-most-for:10m}",
            lockAtLeastFor = "${lego.image-hosting.sync.scheduled.lock-at-least-for:0s}"
    )
    public void runScheduled() {
        runOnce();
    }

    ImageHostingScheduledSyncResult runOnce() {
        long startedAt = System.currentTimeMillis();
        ImageHostingSyncProperties.ResolvedProvider provider = properties.resolveProvider(null, null);
        ImageHostingSyncProperties.Scheduled scheduled = properties.getSync().getScheduled();
        int batchSize = scheduled.effectiveBatchSize();
        int concurrency = scheduled.effectiveConcurrency();
        boolean retryFailed = scheduled.isRetryFailed();
        boolean apply = scheduled.isApply();

        log.info(
                "image_hosting.sync_job.started provider={} externalServiceId={} batchSize={} concurrency={} retryFailed={} apply={}",
                provider.provider(),
                provider.externalServiceId(),
                batchSize,
                concurrency,
                retryFailed,
                apply
        );

        List<ImageHostingSyncCandidate> candidates = externalImageDao.findItemInventorySyncCandidates(
                provider.externalServiceId(),
                retryFailed,
                batchSize
        );
        List<Integer> itemInventoryIds = candidates.stream()
                .map(ImageHostingSyncCandidate::itemInventoryId)
                .toList();
        ImageHostingScheduledSyncCandidateCounts candidateCounts =
                ImageHostingScheduledSyncCandidateCounts.from(candidates);

        log.info(
                "image_hosting.sync_job.candidates_selected provider={} externalServiceId={} itemInventoriesDiscovered={} missingAlbumLink={} missingPhotoLink={} missingAlbumMembership={} failedSync={} pendingSync={} metadataChanged={} contentChanged={}",
                provider.provider(),
                provider.externalServiceId(),
                itemInventoryIds.size(),
                candidateCounts.missingAlbumLink(),
                candidateCounts.missingPhotoLink(),
                candidateCounts.missingAlbumMembership(),
                candidateCounts.failedSync(),
                candidateCounts.pendingSync(),
                candidateCounts.metadataChanged(),
                candidateCounts.contentChanged()
        );

        if (itemInventoryIds.isEmpty()) {
            long elapsedMillis = System.currentTimeMillis() - startedAt;
            metricsService.recordScheduledSync(provider.metricsTag(), "no_work", elapsedMillis);
            log.info(
                    "image_hosting.sync_job.no_work provider={} externalServiceId={} elapsedMillis={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    elapsedMillis
            );
            return new ImageHostingScheduledSyncResult(
                    provider.provider(),
                    provider.externalServiceId(),
                    "NO_WORK",
                    0,
                    0,
                    0,
                    elapsedMillis,
                    List.of(),
                    List.of(),
                    List.of(),
                    apply,
                    candidateCounts
            );
        }

        ExecutorService executor = Executors.newFixedThreadPool(concurrency, threadFactory());
        try {
            List<CompletableFuture<InventorySyncResult>> futures = itemInventoryIds.stream()
                    .map(itemInventoryId -> CompletableFuture.supplyAsync(
                            () -> syncItemInventory(itemInventoryId, provider, apply),
                            executor
                    ))
                    .toList();
            List<InventorySyncResult> inventoryResults = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
            List<Integer> syncedItemInventoryIds = new ArrayList<>();
            List<Integer> failedItemInventoryIds = new ArrayList<>();

            for (InventorySyncResult inventoryResult : inventoryResults) {
                if (inventoryResult.synced()) {
                    syncedItemInventoryIds.add(inventoryResult.itemInventoryId());
                } else {
                    failedItemInventoryIds.add(inventoryResult.itemInventoryId());
                }
            }

            long elapsedMillis = System.currentTimeMillis() - startedAt;
            String outcome = outcome(syncedItemInventoryIds.size(), failedItemInventoryIds.size());
            metricsService.recordScheduledSync(provider.metricsTag(), outcome.toLowerCase(), elapsedMillis);
            metricsService.recordScheduledSyncInventory(provider.metricsTag(), "synced", syncedItemInventoryIds.size());
            metricsService.recordScheduledSyncInventory(provider.metricsTag(), "failed", failedItemInventoryIds.size());

            log.info(
                    "image_hosting.sync_job.completed provider={} externalServiceId={} outcome={} itemInventoriesDiscovered={} itemInventoriesSynced={} itemInventoriesFailed={} elapsedMillis={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    outcome,
                    itemInventoryIds.size(),
                    syncedItemInventoryIds.size(),
                    failedItemInventoryIds.size(),
                    elapsedMillis
            );

            return new ImageHostingScheduledSyncResult(
                    provider.provider(),
                    provider.externalServiceId(),
                    outcome,
                    itemInventoryIds.size(),
                    syncedItemInventoryIds.size(),
                    failedItemInventoryIds.size(),
                    elapsedMillis,
                    itemInventoryIds,
                    syncedItemInventoryIds,
                    failedItemInventoryIds,
                    apply,
                    candidateCounts
            );
        } finally {
            executor.shutdown();
        }
    }

    private InventorySyncResult syncItemInventory(
            Integer itemInventoryId,
            ImageHostingSyncProperties.ResolvedProvider provider,
            boolean apply
    ) {
        try {
            SyncPlan plan = syncPlanService.plan(ImageHostingSyncPlanRequest.builder()
                    .itemInventoryId(itemInventoryId)
                    .provider(provider.provider())
                    .externalServiceId(provider.externalServiceId())
                    .build());
            if (!plan.hasActions()) {
                log.info(
                        "image_hosting.sync_job.inventory_no_actions provider={} externalServiceId={} itemInventoryId={} planId={}",
                        provider.provider(),
                        provider.externalServiceId(),
                        itemInventoryId,
                        plan.getPlanId()
                );
                return new InventorySyncResult(itemInventoryId, true);
            }

            if (!apply) {
                log.info(
                        "image_hosting.sync_job.inventory_planned provider={} externalServiceId={} itemInventoryId={} planId={} plannedActions={} apply=false",
                        provider.provider(),
                        provider.externalServiceId(),
                        itemInventoryId,
                        plan.getPlanId(),
                        plan.getActions().size()
                );
                return new InventorySyncResult(itemInventoryId, true);
            }

            SyncReport report = syncPlanExecutor.execute(plan, false);
            SyncReportSummary summary = report.getSummary();
            boolean synced = !report.hasFailures();
            log.info(
                    "image_hosting.sync_job.inventory_completed provider={} externalServiceId={} itemInventoryId={} planId={} reportId={} synced={} plannedActions={} succeededActions={} failedActions={} blockedActions={} skippedActions={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    itemInventoryId,
                    plan.getPlanId(),
                    report.getReportId(),
                    synced,
                    plan.getActions().size(),
                    summary.getSucceeded(),
                    summary.getFailed(),
                    summary.getBlocked(),
                    summary.getSkipped()
            );
            return new InventorySyncResult(itemInventoryId, synced);
        } catch (RuntimeException e) {
            log.warn(
                    "image_hosting.sync_job.inventory_failed provider={} externalServiceId={} itemInventoryId={} message={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    itemInventoryId,
                    e.getMessage(),
                    e
            );
            return new InventorySyncResult(itemInventoryId, false);
        }
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("image-hosting-sync-" + sequence.incrementAndGet());
            return thread;
        };
    }

    private static String outcome(int syncedCount, int failedCount) {
        if (failedCount == 0) {
            return "SUCCESS";
        }
        if (syncedCount == 0) {
            return "FAILED";
        }
        return "PARTIAL_FAILURE";
    }

    private record InventorySyncResult(Integer itemInventoryId, boolean synced) {
    }
}
