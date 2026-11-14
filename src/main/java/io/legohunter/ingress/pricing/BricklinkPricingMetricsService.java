package io.legohunter.ingress.pricing;

import io.legohunter.data.dao.CrawlTargetDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dao.PricingApplyReadinessDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.ingress.marketplace.bricklink.BricklinkMarketplaceSyncResult;
import io.legohunter.ingress.marketplace.bricklink.BricklinkMarketplaceSyncService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class BricklinkPricingMetricsService {
    private static final String OUTCOME_TAG = "outcome";
    private static final String RESULT_TAG = "result";
    private static final String STATE_TAG = "state";
    private static final String STATUS_TAG = "status";
    private static final List<String> APPLY_READINESS_STATUS_CODES = List.of(
            "READY_TO_APPLY",
            "READY_TO_APPLY_INITIAL_PRICE",
            "BLOCKED_FIXED_PRICE",
            "BLOCKED_MISSING_CURRENT_PRICE",
            "BLOCKED_MISSING_FINAL_PRICE",
            "BLOCKED_CURRENCY_MISMATCH",
            "BLOCKED_UNSUPPORTED_DECISION_STATUS",
            "BLOCKED_REASON_CODE",
            "BLOCKED_INELIGIBLE_REASON",
            "BLOCKED_BELOW_MINIMUM_DELTA",
            "BLOCKED_BELOW_MINIMUM_DELTA_PERCENT",
            "BLOCKED_BELOW_MINIMUM_CONFIDENCE",
            "BLOCKED_BELOW_MINIMUM_COMPARABLE_COUNT",
            "BLOCKED_ABOVE_MAXIMUM_ABSOLUTE_DELTA",
            "BLOCKED_ABOVE_MAXIMUM_PERCENT_DELTA",
            "BLOCKED_STALE_DECISION"
    );
    private static final List<String> APPLY_READINESS_BLOCK_REASON_CODES = List.of(
            "FIXED_PRICE",
            "MISSING_CURRENT_PRICE",
            "MISSING_FINAL_PRICE",
            "CURRENCY_MISMATCH",
            "UNSUPPORTED_DECISION_STATUS",
            "BLOCKED_REASON_CODE",
            "INELIGIBLE_REASON",
            "BELOW_MINIMUM_DELTA",
            "BELOW_MINIMUM_DELTA_PERCENT",
            "BELOW_MINIMUM_CONFIDENCE",
            "BELOW_MINIMUM_COMPARABLE_COUNT",
            "ABOVE_MAXIMUM_ABSOLUTE_DELTA",
            "ABOVE_MAXIMUM_PERCENT_DELTA",
            "STALE_DECISION"
    );

    private final MeterRegistry meterRegistry;
    private final CrawlTargetDao crawlTargetDao;
    private final PricingDecisionDao pricingDecisionDao;
    private final PricingApplyReadinessDao pricingApplyReadinessDao;
    private final MarketplaceListingSyncRequestDao marketplaceListingSyncRequestDao;
    private boolean gaugesRegistered;

    /**
     * Per-run crawl counters (HOMELAB-32). A ban or block shows as {@code bricklink_pricing_crawl_fetch{result="failed"}}
     * rising while {@code succeeded} stays flat; {@code empty} counts fetches that found no lots for sale.
     */
    public void recordCrawl(BricklinkPricingCrawlResult result) {
        registerGauges();
        recordJob("bricklink_pricing_crawl_job", "bricklink_pricing_crawl_job_duration", result.outcome(), result.elapsedMillis());
        increment("bricklink_pricing_crawl_target", "created", result.targetsCreated());
        increment("bricklink_pricing_crawl_target", "reenabled", result.targetsReenabled());
        increment("bricklink_pricing_crawl_target", "disabled", result.targetsDisabled());
        increment("bricklink_pricing_crawl_target", "claimed", result.targetsClaimed());
        increment("bricklink_pricing_crawl_fetch", "succeeded", result.snapshotsWritten());
        increment("bricklink_pricing_crawl_fetch", "empty", result.emptySnapshots());
        increment("bricklink_pricing_crawl_fetch", "failed", result.failedFetches());
        increment("bricklink_pricing_crawl_listing", "written", result.listingsWritten());
        increment("bricklink_pricing_crawl_item", "id_item_resolved", result.idItemsResolved());
    }

    /** Per-run want list counters for this environment (HOMELAB-32). */
    public void recordWantList(BricklinkWantListSyncResult result) {
        increment("bricklink_pricing_want_list_item", "confirmed", result.itemsConfirmed());
        increment("bricklink_pricing_want_list_item", "canonical_item_added", result.canonicalItemsAdded());
        increment("bricklink_pricing_want_list_item", "skipped", result.itemsSkipped());
        increment("bricklink_pricing_want_list_item", "pruned", result.itemsPruned());
    }

    public void recordDecision(BricklinkPricingDecisionResult result) {
        registerGauges();
        recordJob("bricklink_pricing_decision_job", "bricklink_pricing_decision_job_duration", result.outcome(), result.elapsedMillis());
        increment("bricklink_pricing_decision_listing", "selected", result.listingsSelected());
        increment("bricklink_pricing_decision", "written", result.decisionsWritten());
        increment("bricklink_pricing_decision", "proposed", result.proposedDecisions());
        increment("bricklink_pricing_decision", "skipped", result.skippedDecisions());
        increment("bricklink_pricing_decision", "failed", result.failedDecisions());
    }

    public void recordApplyReadiness(BricklinkPricingApplyReadinessResult result) {
        registerGauges();
        recordJob(
                "bricklink_pricing_apply_readiness_job",
                "bricklink_pricing_apply_readiness_job_duration",
                result.outcome(),
                result.elapsedMillis()
        );
        increment("bricklink_pricing_apply_readiness_decision", "selected", result.decisionsSelected());
        increment("bricklink_pricing_apply_readiness_decision", "ready_to_apply", result.readyToApply());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_fixed_price", result.skippedFixedPrice());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_missing_current_price", result.skippedMissingCurrentPrice());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_missing_final_price", result.skippedMissingFinalPrice());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_currency_mismatch", result.skippedCurrencyMismatch());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_unsupported_decision_status", result.skippedUnsupportedDecisionStatus());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_blocked_reason_code", result.skippedBlockedReasonCode());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_ineligible_reason", result.skippedIneligibleReason());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_below_minimum_delta", result.skippedBelowMinimumDelta());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_below_minimum_delta_percent", result.skippedBelowMinimumDeltaPercent());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_below_minimum_confidence", result.skippedBelowMinimumConfidence());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_below_minimum_comparable_count", result.skippedBelowMinimumComparableCount());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_above_maximum_absolute_delta", result.skippedAboveMaximumAbsoluteDelta());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_above_maximum_percent_delta", result.skippedAboveMaximumPercentDelta());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_stale_decision", result.skippedStaleDecision());
    }

    public void recordApply(BricklinkPricingApplyResult result) {
        registerGauges();
        recordJob("bricklink_pricing_apply_job", "bricklink_pricing_apply_job_duration", result.outcome(), result.elapsedMillis());
        increment("bricklink_pricing_apply_decision", "selected", result.readinessRowsSelected());
        increment("bricklink_pricing_apply_decision", "local_price_updated", result.localPricesUpdated());
        increment("bricklink_pricing_apply_decision", "sync_request_enqueued", result.syncRequestsEnqueued());
        increment("bricklink_pricing_apply_decision", "dry_run_selected", result.dryRunSelections());
        increment("bricklink_pricing_apply_decision", "skipped", result.skippedRows());
        increment("bricklink_pricing_apply_decision", "failed", result.failedRows());
    }

    public void recordReprice(RepriceResult result) {
        registerGauges();
        recordJob("pricing_reprice_job", "pricing_reprice_job_duration", result.outcome(), result.elapsedMillis());
        // Registered on every run, even at zero: Reprice moves these only a few times an hour, and a counter
        // that first appears already at 1 shows no increase() in Grafana, so its first event would be lost.
        count("pricing_reprice_listing", "selected", result.listingsSelected());
        count("pricing_reprice_listing", "waiting_for_sync", result.waitingForSync());
        count("pricing_reprice_listing", "error", result.errors());
        count("pricing_reprice_decision", "proposed", result.proposed());
        count("pricing_reprice_decision", "blocked", result.blocked());
        count("pricing_reprice_decision", "skipped", result.skipped());
        count("pricing_reprice_decision", "failed", result.failed());
        count("pricing_reprice_decision", "accepted", result.accepted());
        count("pricing_reprice_decision", "applied_locally", result.appliedLocally());
        count("pricing_reprice_sync", "handed_off", result.handedOff());
        count("pricing_reprice_sync", "confirmed", result.syncConfirmed());
    }

    /** Like {@link #increment} but registers the counter even when {@code count} is zero. */
    private void count(String metricName, String result, double count) {
        Counter counter = Counter.builder(metricName)
                .tag(RESULT_TAG, result)
                .register(meterRegistry);
        if (count > 0) {
            counter.increment(count);
        }
    }

    public void recordMarketplaceSync(BricklinkMarketplaceSyncResult result) {
        registerGauges();
        recordJob("bricklink_marketplace_sync_job", "bricklink_marketplace_sync_job_duration", result.outcome(), result.elapsedMillis());
        increment("bricklink_marketplace_sync_request", "selected", result.requestsSelected());
        increment("bricklink_marketplace_sync_request", "claimed", result.requestsClaimed());
        increment("bricklink_marketplace_sync_request", "remote_verified", result.remoteVerified());
        increment("bricklink_marketplace_sync_request", "remote_updated", result.remoteUpdated());
        increment("bricklink_marketplace_sync_request", "dry_run_verified", result.dryRunVerified());
        increment("bricklink_marketplace_sync_request", "blocked", result.blocked());
        increment("bricklink_marketplace_sync_request", "failed", result.failed());
    }

    private synchronized void registerGauges() {
        if (gaugesRegistered) {
            return;
        }
        String marketplace = BricklinkPricingCrawlService.MARKETPLACE_CODE;
        registerCrawlGauge("idle", () -> crawlTargetDao.countByTargetStatusCode(marketplace, "IDLE"));
        registerCrawlGauge("claimed", () -> crawlTargetDao.countByTargetStatusCode(marketplace, "CLAIMED"));
        registerCrawlGauge("disabled", () -> crawlTargetDao.countByTargetStatusCode(marketplace, "DISABLED"));
        registerCrawlGauge("due", () -> crawlTargetDao.countDue(marketplace, now()));
        registerCrawlGauge("failing", () -> crawlTargetDao.countFailing(marketplace));

        registerDecisionGauge(BricklinkPricingDecisionService.STATUS_PROPOSED, false);
        registerDecisionGauge(BricklinkPricingDecisionService.STATUS_FAILED, false);
        registerDecisionGauge(BricklinkPricingDecisionService.STATUS_SKIPPED, false);
        registerDecisionGauge(BricklinkPricingDecisionService.STATUS_PROPOSED, true);
        registerDecisionGauge(BricklinkPricingApplyService.STATUS_ACCEPTED, false);
        registerDecisionGauge(BricklinkPricingApplyService.STATUS_SYNC_FAILED, false);
        registerDecisionGauge(RepriceService.STATUS_BLOCKED, false);
        APPLY_READINESS_STATUS_CODES.forEach(this::registerApplyReadinessGauge);
        APPLY_READINESS_BLOCK_REASON_CODES.forEach(this::registerApplyReadinessBlockReasonGauge);
        registerMarketplaceSyncGauge("pending", BricklinkMarketplaceSyncService.STATUS_PENDING);
        registerMarketplaceSyncGauge("claimed", BricklinkMarketplaceSyncService.STATUS_CLAIMED);
        registerMarketplaceSyncGauge("succeeded", BricklinkMarketplaceSyncService.STATUS_SUCCEEDED);
        registerMarketplaceSyncGauge("blocked", BricklinkMarketplaceSyncService.STATUS_BLOCKED);
        registerMarketplaceSyncGauge("failed", BricklinkMarketplaceSyncService.STATUS_FAILED);
        registerMarketplaceSyncDueGauge();
        gaugesRegistered = true;
    }

    private void registerCrawlGauge(String state, CountSupplier supplier) {
        Gauge.builder("bricklink_pricing_crawl_target_current", this, ignored -> supplier.count())
                .description("Current shared BrickLink crawl target count by state (marketplace_pricing.crawl_target).")
                .tag(STATE_TAG, state)
                .strongReference(true)
                .register(meterRegistry);
    }

    private void registerDecisionGauge(String status, boolean unappliedOnly) {
        Gauge.builder("bricklink_pricing_decision_current", this, ignored -> decisionCount(status, unappliedOnly))
                .description("Current latest BrickLink pricing decision count by status.")
                .tag(STATUS_TAG, status.toLowerCase())
                .tag("unapplied_only", Boolean.toString(unappliedOnly))
                .strongReference(true)
                .register(meterRegistry);
    }

    private void registerApplyReadinessGauge(String status) {
        Gauge.builder(
                        "bricklink_pricing_apply_readiness_current",
                        this,
                        ignored -> pricingApplyReadinessDao.countLatestByReadinessStatusCode(status)
                )
                .description("Current latest BrickLink pricing apply-readiness count by status.")
                .tag(STATUS_TAG, status.toLowerCase())
                .strongReference(true)
                .register(meterRegistry);
    }

    private void registerApplyReadinessBlockReasonGauge(String reason) {
        Gauge.builder(
                        "bricklink_pricing_apply_readiness_block_reason_current",
                        this,
                        ignored -> pricingApplyReadinessDao.countLatestByBlockReasonCode(reason)
                )
                .description("Current latest BrickLink pricing apply-readiness count by block reason.")
                .tag("reason", reason.toLowerCase())
                .strongReference(true)
                .register(meterRegistry);
    }

    private void registerMarketplaceSyncGauge(String state, String statusCode) {
        Gauge.builder(
                        "bricklink_marketplace_sync_request_current",
                        this,
                        ignored -> marketplaceListingSyncRequestDao.countBySyncRequestStatusCode(statusCode)
                )
                .description("Current BrickLink marketplace listing sync request count by state.")
                .tag(STATE_TAG, state)
                .strongReference(true)
                .register(meterRegistry);
    }

    private void registerMarketplaceSyncDueGauge() {
        Gauge.builder(
                        "bricklink_marketplace_sync_request_current",
                        this,
                        ignored -> marketplaceListingSyncRequestDao.countDueBySyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_PENDING, now())
                )
                .description("Current BrickLink marketplace listing sync request count by state.")
                .tag(STATE_TAG, "due")
                .strongReference(true)
                .register(meterRegistry);
    }

    private long decisionCount(String status, boolean unappliedOnly) {
        if (unappliedOnly) {
            return pricingDecisionDao.countLatestUnappliedByDecisionStatusCode(status);
        }
        return pricingDecisionDao.countLatestByDecisionStatusCode(status);
    }

    private void recordJob(String counterName, String timerName, String outcome, long elapsedMillis) {
        Counter.builder(counterName)
                .tag(OUTCOME_TAG, tagValue(outcome))
                .register(meterRegistry)
                .increment();

        Timer.builder(timerName)
                .tag(OUTCOME_TAG, tagValue(outcome))
                .register(meterRegistry)
                .record(elapsedMillis, TimeUnit.MILLISECONDS);
    }

    private void increment(String metricName, String result, double count) {
        if (count <= 0) {
            return;
        }
        Counter.builder(metricName)
                .tag(RESULT_TAG, result)
                .register(meterRegistry)
                .increment(count);
    }

    private String tagValue(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase();
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    @FunctionalInterface
    private interface CountSupplier {
        long count();
    }
}
