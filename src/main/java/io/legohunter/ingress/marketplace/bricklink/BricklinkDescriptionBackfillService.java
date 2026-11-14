package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.ingress.pricing.BricklinkPricingApplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Queues a DESCRIPTION_UPDATE for every owned lot whose Description does not show its listing's description or
 * title (HOMELAB-80): the one-off fix for lots created before listings had titles, and the fix for any later
 * DESCRIPTION_DRIFT that reconciliation reports.
 *
 * <p>It reads the drift from a fresh reconciliation, which already skips listings with a change in flight, so a
 * second run never queues a duplicate. With {@code apply=false} it only reports what it would queue. The requests
 * are sent by the marketplace sync job like any other, with its ownership, stockroom and read-back guards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lego.bricklink.marketplace-sync", name = "enabled", havingValue = "true")
public class BricklinkDescriptionBackfillService {
    static final String SYNC_REASON = "DESCRIPTION_DRIFT_BACKFILL";
    static final String CREATED_BY = "BricklinkDescriptionBackfill";
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
                requestId = marketplaceListingSyncRequestDao.insert(request(drift)).getMarketplaceListingSyncRequestId();
            }
            rows.add(new Row(drift.marketplaceListingId(), drift.bricklinkInventoryId(), drift.expected(), drift.actual(), requestId));
        }
        log.info("bricklink.description_backfill.completed apply={} reconciliationOutcome={} descriptionDrifts={} queued={}",
                apply, report.outcome(), rows.size(), apply ? rows.size() : 0);
        return java.util.Optional.of(new Result(apply, report.outcome(), List.copyOf(rows)));
    }

    private MarketplaceListingSyncRequest request(BricklinkInventoryReconciliationReport.Drift drift) {
        boolean nonProd = properties.nonProdEnvironment();
        return MarketplaceListingSyncRequest.builder()
                .marketplaceListingId(drift.marketplaceListingId())
                .listingExternalServiceId(properties.getBricklinkExternalServiceId())
                .syncRequestTypeCode(BricklinkMarketplaceSyncService.TYPE_DESCRIPTION_UPDATE)
                .syncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_PENDING)
                .syncReasonCode(SYNC_REASON)
                .remoteInventoryId(String.valueOf(drift.bricklinkInventoryId()))
                .remoteVisibilityScopeCode(nonProd ? BricklinkPricingApplyService.REMOTE_SCOPE_STOCKROOM : null)
                .remoteVisibilityContainerId(nonProd ? properties.effectiveNonProdStockRoomId() : null)
                .remoteIsPubliclyAvailable(nonProd ? Boolean.FALSE : null)
                .environmentCode(properties.effectiveEnvironmentCode())
                .createdByJobName(CREATED_BY)
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
