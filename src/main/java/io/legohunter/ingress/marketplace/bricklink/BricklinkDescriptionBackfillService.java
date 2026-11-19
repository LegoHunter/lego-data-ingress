package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.ingress.marketplace.MarketplaceSyncCodes;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Queues a DESCRIPTION_UPDATE for every owned lot whose Description does not show its listing's description or
 * title (HOMELAB-80): the one-off fix for lots created before listings had titles, and the fix for any later
 * DESCRIPTION_DRIFT that reconciliation reports.
 *
 * <p>It reads the drift from a fresh reconciliation, which already skips listings with a change in flight, so a
 * second run never queues a duplicate. With {@code apply=false} it only reports what it would queue. The requests
 * are sent by the marketplace sync job like any other, with its ownership, stockroom and read-back guards.
 *
 * <p>The scheduled reconciliation also hands its drift here when {@code queue-description-updates} is on
 * (HOMELAB-82): that is how a new album link or a caption added or removed reaches the lot. There, a listing whose
 * last DESCRIPTION_UPDATE ended without success within the retry backoff is skipped, so a lot that cannot take its
 * text is not queued again every hour. A DESCRIPTION_TOO_LONG drift is never queued: only the owner can fix it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lego.bricklink.marketplace-sync", name = "enabled", havingValue = "true")
public class BricklinkDescriptionBackfillService {
    static final String SYNC_REASON = "DESCRIPTION_DRIFT_BACKFILL";
    static final String CREATED_BY = "BricklinkDescriptionBackfill";
    static final String SYNC_REASON_DRIFT = "DESCRIPTION_DRIFT";
    static final String CREATED_BY_RECONCILIATION = "BricklinkInventoryReconciliationJob";
    private static final int MAX_ATTEMPTS = 3;

    private final ObjectProvider<BricklinkInventoryReconciliationService> reconciliationService;
    private final BricklinkMarketplaceSyncProperties properties;
    private final MarketplaceListingSyncRequestDao marketplaceListingSyncRequestDao;

    /** Empty when reconciliation is not enabled here, since without it there is no drift to read. */
    public java.util.Optional<Result> backfill(boolean apply) {
        BricklinkInventoryReconciliationService reconciliation = reconciliationService.getIfAvailable();
        if (reconciliation == null) {
            return java.util.Optional.empty();
        }
        BricklinkInventoryReconciliationReport report = reconciliation.reconcile();
        List<Row> rows = new ArrayList<>();
        for (BricklinkInventoryReconciliationReport.Drift drift : report.drifts()) {
            if (drift.type() != BricklinkInventoryDriftType.DESCRIPTION_DRIFT) {
                continue;
            }
            Long requestId = null;
            if (apply) {
                requestId = marketplaceListingSyncRequestDao.insert(request(drift, SYNC_REASON, CREATED_BY)).getMarketplaceListingSyncRequestId();
            }
            rows.add(new Row(drift.marketplaceListingId(), drift.bricklinkInventoryId(), drift.expected(), drift.actual(), requestId));
        }
        log.info("bricklink.description_backfill.completed apply={} reconciliationOutcome={} descriptionDrifts={} queued={}",
                apply, report.outcome(), rows.size(), apply ? rows.size() : 0);
        return java.util.Optional.of(new Result(apply, report.outcome(), List.copyOf(rows)));
    }

    /**
     * Queues a DESCRIPTION_UPDATE for each DESCRIPTION_DRIFT of a scheduled reconciliation (HOMELAB-82), skipping a
     * listing whose last DESCRIPTION_UPDATE ended without success within the retry backoff.
     *
     * @return how many requests were queued
     */
    public int queueDrift(BricklinkInventoryReconciliationReport report) {
        int queued = 0;
        int heldBack = 0;
        ZonedDateTime retryNotBefore = now().minus(properties.effectiveRetryBackoff());
        for (BricklinkInventoryReconciliationReport.Drift drift : report.drifts()) {
            if (drift.type() != BricklinkInventoryDriftType.DESCRIPTION_DRIFT || drift.marketplaceListingId() == null) {
                continue;
            }
            if (failedRecently(drift.marketplaceListingId(), retryNotBefore)) {
                heldBack++;
                continue;
            }
            MarketplaceListingSyncRequest request = marketplaceListingSyncRequestDao.insert(request(drift, SYNC_REASON_DRIFT, CREATED_BY_RECONCILIATION));
            queued++;
            log.info("bricklink.description_drift.queued marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={}",
                    request.getMarketplaceListingSyncRequestId(), drift.marketplaceListingId(), drift.bricklinkInventoryId());
        }
        if (queued > 0 || heldBack > 0) {
            log.info("bricklink.description_drift.completed queued={} heldBack={}", queued, heldBack);
        }
        return queued;
    }

    private boolean failedRecently(Integer marketplaceListingId, ZonedDateTime retryNotBefore) {
        return marketplaceListingSyncRequestDao.findByMarketplaceListingIdAndSyncRequestTypeCodeAndSyncRequestStatusCodes(
                        marketplaceListingId,
                        BricklinkMarketplaceSyncService.TYPE_DESCRIPTION_UPDATE,
                        Set.of(BricklinkMarketplaceSyncService.STATUS_BLOCKED, BricklinkMarketplaceSyncService.STATUS_FAILED))
                .stream()
                .map(request -> request.getUpdatedAt() != null ? request.getUpdatedAt() : request.getCompletedAt())
                .anyMatch(endedAt -> endedAt != null && endedAt.isAfter(retryNotBefore));
    }

    private MarketplaceListingSyncRequest request(BricklinkInventoryReconciliationReport.Drift drift, String syncReason, String createdBy) {
        boolean nonProd = properties.nonProdEnvironment();
        return MarketplaceListingSyncRequest.builder()
                .marketplaceListingId(drift.marketplaceListingId())
                .listingExternalServiceId(properties.getBricklinkExternalServiceId())
                .syncRequestTypeCode(BricklinkMarketplaceSyncService.TYPE_DESCRIPTION_UPDATE)
                .syncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_PENDING)
                .syncReasonCode(syncReason)
                .remoteInventoryId(String.valueOf(drift.bricklinkInventoryId()))
                .remoteVisibilityScopeCode(nonProd ? MarketplaceSyncCodes.REMOTE_SCOPE_STOCKROOM : null)
                .remoteVisibilityContainerId(nonProd ? properties.effectiveNonProdStockRoomId() : null)
                .remoteIsPubliclyAvailable(nonProd ? Boolean.FALSE : null)
                .environmentCode(properties.effectiveEnvironmentCode())
                .createdByJobName(createdBy)
                .attemptCount(0)
                .maxAttempts(MAX_ATTEMPTS)
                .nextAttemptAt(now())
                .build();
    }

    ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    public record Result(boolean applied, String reconciliationOutcome, List<Row> rows) {
    }

    /** One drifted lot; {@code syncRequestId} is set when the request was queued. */
    public record Row(Integer marketplaceListingId, Long bricklinkInventoryId, String expectedDescription,
                      String currentDescription, Long syncRequestId) {
    }
}
