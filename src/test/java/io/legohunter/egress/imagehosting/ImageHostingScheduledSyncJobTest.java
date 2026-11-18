package io.legohunter.egress.imagehosting;

import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dao.ImageHostingSyncCandidate;
import io.legohunter.data.dao.ImageHostingSyncCandidateReason;
import io.legohunter.egress.imagehosting.plan.ImageHostingSyncPlanExecutor;
import io.legohunter.egress.imagehosting.plan.ImageHostingSyncPlanRequest;
import io.legohunter.egress.imagehosting.plan.ImageHostingSyncPlanService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.legohunter.imaging.service.sync.model.SyncAction;
import io.legohunter.imaging.service.sync.model.SyncActionResult;
import io.legohunter.imaging.service.sync.model.SyncActionSafety;
import io.legohunter.imaging.service.sync.model.SyncActionStatus;
import io.legohunter.imaging.service.sync.model.SyncActionType;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.imaging.service.sync.model.SyncPlanMode;
import io.legohunter.imaging.service.sync.model.SyncReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageHostingScheduledSyncJobTest {
    private static final int FLICKR_SERVICE_ID = 10;

    @Mock
    private ExternalImageDao externalImageDao;

    @Mock
    private ImageHostingSyncPlanService syncPlanService;

    @Mock
    private ImageHostingSyncPlanExecutor syncPlanExecutor;

    private ImageHostingSyncProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private ImageHostingScheduledSyncJob job;

    @BeforeEach
    void setUp() {
        properties = new ImageHostingSyncProperties();
        ImageHostingSyncProperties.Provider flickr = new ImageHostingSyncProperties.Provider();
        flickr.setEnabled(true);
        flickr.setExternalServiceId(FLICKR_SERVICE_ID);
        flickr.setMetricsTag("flickr");
        properties.getProviders().put("flickr", flickr);
        properties.getSync().getScheduled().setBatchSize(3);
        properties.getSync().getScheduled().setConcurrency(2);

        meterRegistry = new SimpleMeterRegistry();
        job = new ImageHostingScheduledSyncJob(
                externalImageDao,
                syncPlanService,
                syncPlanExecutor,
                properties,
                new ImageHostingSyncMetricsService(meterRegistry)
        );
    }

    @Test
    void runOnceRecordsNoWorkWhenDiscoveryReturnsNoInventories() {
        when(externalImageDao.findItemInventorySyncCandidates(FLICKR_SERVICE_ID, true, 3))
                .thenReturn(List.of());

        ImageHostingScheduledSyncResult result = job.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_WORK");
        assertThat(result.itemInventoriesDiscovered()).isZero();
        assertThat(result.itemInventoryIds()).isEmpty();
        assertThat(result.apply()).isFalse();
        assertThat(result.candidateCounts().missingAlbumLink()).isZero();
        assertThat(result.candidateCounts().missingAlbumMembership()).isZero();
        verifyNoInteractions(syncPlanService, syncPlanExecutor);
        assertThat(meterRegistry.counter(
                "image_hosting_scheduled_sync",
                "provider", "flickr",
                "outcome", "no_work"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void runOncePlansDiscoveredInventoriesWithoutApplyingWhenApplyIsDisabled() {
        when(externalImageDao.findItemInventorySyncCandidates(FLICKR_SERVICE_ID, true, 3))
                .thenReturn(List.of(
                        candidate(101, ImageHostingSyncCandidateReason.MISSING_ALBUM_LINK),
                        candidate(102, ImageHostingSyncCandidateReason.MISSING_PHOTO_LINK),
                        new ImageHostingSyncCandidate(
                                103,
                                Set.of(
                                        ImageHostingSyncCandidateReason.MISSING_ALBUM_MEMBERSHIP,
                                        ImageHostingSyncCandidateReason.METADATA_CHANGED,
                                        ImageHostingSyncCandidateReason.CONTENT_CHANGED
                                )
                        )
                ));
        when(syncPlanService.plan(any())).thenAnswer(invocation -> {
            ImageHostingSyncPlanRequest request = invocation.getArgument(0);
            return plan(request.getItemInventoryId());
        });

        ImageHostingScheduledSyncResult result = job.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.itemInventoryIds()).containsExactly(101, 102, 103);
        assertThat(result.syncedItemInventoryIds()).containsExactly(101, 102, 103);
        assertThat(result.apply()).isFalse();
        assertThat(result.candidateCounts())
                .extracting(
                        ImageHostingScheduledSyncCandidateCounts::missingAlbumLink,
                        ImageHostingScheduledSyncCandidateCounts::missingPhotoLink,
                        ImageHostingScheduledSyncCandidateCounts::missingAlbumMembership,
                        ImageHostingScheduledSyncCandidateCounts::metadataChanged,
                        ImageHostingScheduledSyncCandidateCounts::contentChanged
                )
                .containsExactly(1, 1, 1, 1, 1);
        verify(syncPlanService, times(3)).plan(any());
        verifyNoInteractions(syncPlanExecutor);
    }

    @Test
    void runOnceSyncsDiscoveredInventoriesWithConfiguredConcurrencyWhenApplyIsEnabled() throws Exception {
        properties.getSync().getScheduled().setApply(true);
        CountDownLatch firstTwoStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        when(externalImageDao.findItemInventorySyncCandidates(FLICKR_SERVICE_ID, true, 3))
                .thenReturn(List.of(
                        candidate(101, ImageHostingSyncCandidateReason.MISSING_ALBUM_LINK),
                        candidate(102, ImageHostingSyncCandidateReason.MISSING_PHOTO_LINK),
                        candidate(103, ImageHostingSyncCandidateReason.FAILED_SYNC)
                ));
        when(syncPlanService.plan(any())).thenAnswer(invocation -> {
            ImageHostingSyncPlanRequest request = invocation.getArgument(0);
            int currentActive = active.incrementAndGet();
            maxActive.accumulateAndGet(currentActive, Math::max);
            firstTwoStarted.countDown();
            release.await(2, TimeUnit.SECONDS);
            active.decrementAndGet();
            return plan(request.getItemInventoryId());
        });
        when(syncPlanExecutor.execute(any(), eq(false))).thenReturn(successReport("plan"));

        CompletableFuture<ImageHostingScheduledSyncResult> future = CompletableFuture.supplyAsync(job::runOnce);
        assertThat(firstTwoStarted.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        ImageHostingScheduledSyncResult result = future.get(2, TimeUnit.SECONDS);

        assertThat(maxActive.get()).isEqualTo(2);
        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.apply()).isTrue();
        assertThat(result.itemInventoryIds()).containsExactly(101, 102, 103);
        assertThat(result.syncedItemInventoryIds()).containsExactly(101, 102, 103);
        assertThat(result.failedItemInventoryIds()).isEmpty();
        ArgumentCaptor<ImageHostingSyncPlanRequest> requestCaptor = ArgumentCaptor.forClass(ImageHostingSyncPlanRequest.class);
        verify(syncPlanService, times(3)).plan(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
                .extracting(ImageHostingSyncPlanRequest::getItemInventoryId)
                .containsExactlyInAnyOrder(101, 102, 103);
        assertThat(requestCaptor.getAllValues())
                .allSatisfy(request -> {
                    assertThat(request.getProvider()).isEqualTo("flickr");
                    assertThat(request.getExternalServiceId()).isEqualTo(FLICKR_SERVICE_ID);
                });
        verify(syncPlanExecutor, times(3)).execute(any(), eq(false));
        assertThat(meterRegistry.counter(
                "image_hosting_scheduled_sync_inventory",
                "provider", "flickr",
                "result", "synced"
        ).count()).isEqualTo(3.0);
    }

    @Test
    void runOnceContinuesWhenAnInventoryFails() {
        properties.getSync().getScheduled().setApply(true);
        properties.getSync().getScheduled().setConcurrency(1);
        when(externalImageDao.findItemInventorySyncCandidates(FLICKR_SERVICE_ID, true, 3))
                .thenReturn(List.of(
                        candidate(201, ImageHostingSyncCandidateReason.PENDING_SYNC),
                        candidate(202, ImageHostingSyncCandidateReason.FAILED_SYNC),
                        candidate(203, ImageHostingSyncCandidateReason.METADATA_CHANGED)
                ));
        when(syncPlanService.plan(any())).thenAnswer(invocation -> {
            ImageHostingSyncPlanRequest request = invocation.getArgument(0);
            if (request.getItemInventoryId() == 202) {
                return plan(202);
            }
            if (request.getItemInventoryId() == 203) {
                throw new IllegalStateException("provider unavailable");
            }
            return plan(201);
        });
        when(syncPlanExecutor.execute(any(), eq(false))).thenAnswer(invocation -> {
            SyncPlan plan = invocation.getArgument(0);
            if ("plan-202".equals(plan.getPlanId())) {
                return failedReport("plan-202");
            }
            return successReport(plan.getPlanId());
        });

        ImageHostingScheduledSyncResult result = job.runOnce();

        assertThat(result.outcome()).isEqualTo("PARTIAL_FAILURE");
        assertThat(result.candidateCounts().failedSync()).isEqualTo(1);
        assertThat(result.candidateCounts().pendingSync()).isEqualTo(1);
        assertThat(result.syncedItemInventoryIds()).containsExactly(201);
        assertThat(result.failedItemInventoryIds()).containsExactly(202, 203);
        assertThat(meterRegistry.counter(
                "image_hosting_scheduled_sync",
                "provider", "flickr",
                "outcome", "partial_failure"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "image_hosting_scheduled_sync_inventory",
                "provider", "flickr",
                "result", "failed"
        ).count()).isEqualTo(2.0);
    }

    private static ImageHostingSyncCandidate candidate(
            Integer itemInventoryId,
            ImageHostingSyncCandidateReason reason
    ) {
        return new ImageHostingSyncCandidate(itemInventoryId, Set.of(reason));
    }

    private static SyncPlan plan(Integer itemInventoryId) {
        return SyncPlan.builder()
                .planId("plan-" + itemInventoryId)
                .mode(SyncPlanMode.DRY_RUN)
                .action(SyncAction.builder()
                        .actionId("001-update-album-metadata")
                        .type(SyncActionType.UPDATE_ALBUM_METADATA)
                        .safety(SyncActionSafety.SAFE_AUTOMATIC)
                        .build())
                .build();
    }

    private static SyncReport successReport(String planId) {
        return SyncReport.builder()
                .reportId("report-" + planId)
                .planId(planId)
                .mode(SyncPlanMode.APPLY)
                .result(SyncActionResult.builder()
                        .actionId("001-update-album-metadata")
                        .type(SyncActionType.UPDATE_ALBUM_METADATA)
                        .status(SyncActionStatus.SUCCEEDED)
                        .build())
                .build();
    }

    private static SyncReport failedReport(String planId) {
        return SyncReport.builder()
                .reportId("report-" + planId)
                .planId(planId)
                .mode(SyncPlanMode.APPLY)
                .result(SyncActionResult.builder()
                        .actionId("001-update-album-metadata")
                        .type(SyncActionType.UPDATE_ALBUM_METADATA)
                        .status(SyncActionStatus.FAILED)
                        .build())
                .build();
    }
}
