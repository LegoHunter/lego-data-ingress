package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dao.PricingApplyReadinessDao;
import io.legohunter.data.dao.CrawlTargetDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingMetricsServiceTest {
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CrawlTargetDao crawlTargetDao = mock(CrawlTargetDao.class);
    private final PricingDecisionDao pricingDecisionDao = mock(PricingDecisionDao.class);
    private final PricingApplyReadinessDao pricingApplyReadinessDao = mock(PricingApplyReadinessDao.class);
    private final MarketplaceListingSyncRequestDao marketplaceListingSyncRequestDao = mock(MarketplaceListingSyncRequestDao.class);
    private final BricklinkPricingMetricsService metricsService = new BricklinkPricingMetricsService(
            meterRegistry,
            crawlTargetDao,
            pricingDecisionDao,
            pricingApplyReadinessDao,
            marketplaceListingSyncRequestDao
    );

    @Test
    void recordsSharedCrawlMetricsAndCrawlTargetGauges() {
        when(crawlTargetDao.countByTargetStatusCode("BRICKLINK", "IDLE")).thenReturn(7L);
        when(crawlTargetDao.countByTargetStatusCode("BRICKLINK", "CLAIMED")).thenReturn(1L);
        when(crawlTargetDao.countByTargetStatusCode("BRICKLINK", "DISABLED")).thenReturn(5L);
        when(crawlTargetDao.countDue(eq("BRICKLINK"), any())).thenReturn(2L);
        when(crawlTargetDao.countFailing("BRICKLINK")).thenReturn(4L);

        metricsService.recordCrawl(new BricklinkPricingCrawlResult(
                "PARTIAL_SUCCESS",
                3,   // targets created
                1,   // re-enabled
                2,   // disabled
                4,   // claimed
                3,   // snapshots
                1,   // empty
                99,  // lots
                1,   // idItems resolved
                1,   // failed fetches
                250
        ));

        assertThat(counter("bricklink_pricing_crawl_job", "outcome", "partial_success")).isEqualTo(1.0d);
        assertThat(timerCount("bricklink_pricing_crawl_job_duration", "outcome", "partial_success")).isOne();
        assertThat(counter("bricklink_pricing_crawl_target", "result", "created")).isEqualTo(3.0d);
        assertThat(counter("bricklink_pricing_crawl_target", "result", "reenabled")).isEqualTo(1.0d);
        assertThat(counter("bricklink_pricing_crawl_target", "result", "disabled")).isEqualTo(2.0d);
        assertThat(counter("bricklink_pricing_crawl_target", "result", "claimed")).isEqualTo(4.0d);
        assertThat(counter("bricklink_pricing_crawl_fetch", "result", "succeeded")).isEqualTo(3.0d);
        assertThat(counter("bricklink_pricing_crawl_fetch", "result", "empty")).isEqualTo(1.0d);
        assertThat(counter("bricklink_pricing_crawl_fetch", "result", "failed")).isEqualTo(1.0d);
        assertThat(counter("bricklink_pricing_crawl_listing", "result", "written")).isEqualTo(99.0d);
        assertThat(counter("bricklink_pricing_crawl_item", "result", "id_item_resolved")).isEqualTo(1.0d);
        assertThat(gauge("bricklink_pricing_crawl_target_current", "state", "idle")).isEqualTo(7.0d);
        assertThat(gauge("bricklink_pricing_crawl_target_current", "state", "claimed")).isEqualTo(1.0d);
        assertThat(gauge("bricklink_pricing_crawl_target_current", "state", "disabled")).isEqualTo(5.0d);
        assertThat(gauge("bricklink_pricing_crawl_target_current", "state", "due")).isEqualTo(2.0d);
        assertThat(gauge("bricklink_pricing_crawl_target_current", "state", "failing")).isEqualTo(4.0d);
    }

    @Test
    void recordsWantListCounters() {
        metricsService.recordWantList(new BricklinkWantListSyncResult("sandbox", 12, 2, 1, 3, 40));

        assertThat(counter("bricklink_pricing_want_list_item", "result", "confirmed")).isEqualTo(12.0d);
        assertThat(counter("bricklink_pricing_want_list_item", "result", "canonical_item_added")).isEqualTo(2.0d);
        assertThat(counter("bricklink_pricing_want_list_item", "result", "skipped")).isEqualTo(1.0d);
        assertThat(counter("bricklink_pricing_want_list_item", "result", "pruned")).isEqualTo(3.0d);
    }

    @Test
    void recordsPricingDecisionMetricsAndLatestDecisionGauges() {
        when(pricingDecisionDao.countLatestByDecisionStatusCode(BricklinkPricingDecisionService.STATUS_PROPOSED)).thenReturn(8L);
        when(pricingDecisionDao.countLatestByDecisionStatusCode(BricklinkPricingDecisionService.STATUS_FAILED)).thenReturn(2L);
        when(pricingDecisionDao.countLatestByDecisionStatusCode(BricklinkPricingDecisionService.STATUS_SKIPPED)).thenReturn(1L);
        when(pricingDecisionDao.countLatestUnappliedByDecisionStatusCode(BricklinkPricingDecisionService.STATUS_PROPOSED)).thenReturn(6L);

        metricsService.recordDecision(new BricklinkPricingDecisionResult("PARTIAL_SUCCESS", 10, 9, 6, 1, 2, 125));

        assertThat(counter("bricklink_pricing_decision_job", "outcome", "partial_success")).isEqualTo(1.0d);
        assertThat(timerCount("bricklink_pricing_decision_job_duration", "outcome", "partial_success")).isOne();
        assertThat(counter("bricklink_pricing_decision_listing", "result", "selected")).isEqualTo(10.0d);
        assertThat(counter("bricklink_pricing_decision", "result", "written")).isEqualTo(9.0d);
        assertThat(counter("bricklink_pricing_decision", "result", "proposed")).isEqualTo(6.0d);
        assertThat(gauge("bricklink_pricing_decision_current", "status", "proposed", "unapplied_only", "false")).isEqualTo(8.0d);
        assertThat(gauge("bricklink_pricing_decision_current", "status", "failed", "unapplied_only", "false")).isEqualTo(2.0d);
        assertThat(gauge("bricklink_pricing_decision_current", "status", "skipped", "unapplied_only", "false")).isEqualTo(1.0d);
        assertThat(gauge("bricklink_pricing_decision_current", "status", "proposed", "unapplied_only", "true")).isEqualTo(6.0d);
    }

    @Test
    void recordsApplyReadinessMetrics() {
        when(pricingApplyReadinessDao.countLatestByReadinessStatusCode("READY_TO_APPLY")).thenReturn(7L);
        when(pricingApplyReadinessDao.countLatestByReadinessStatusCode("BLOCKED_MISSING_CURRENT_PRICE")).thenReturn(1L);
        when(pricingApplyReadinessDao.countLatestByBlockReasonCode("MISSING_CURRENT_PRICE")).thenReturn(1L);

        metricsService.recordApplyReadiness(new BricklinkPricingApplyReadinessResult(
                "SUCCESS",
                12,
                7,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                75
        ));

        assertThat(counter("bricklink_pricing_apply_readiness_job", "outcome", "success")).isEqualTo(1.0d);
        assertThat(timerCount("bricklink_pricing_apply_readiness_job_duration", "outcome", "success")).isOne();
        assertThat(counter("bricklink_pricing_apply_readiness_decision", "result", "selected")).isEqualTo(12.0d);
        assertThat(counter("bricklink_pricing_apply_readiness_decision", "result", "ready_to_apply")).isEqualTo(7.0d);
        assertThat(counter("bricklink_pricing_apply_readiness_decision", "result", "skipped_fixed_price")).isEqualTo(1.0d);
        assertThat(counter("bricklink_pricing_apply_readiness_decision", "result", "skipped_missing_current_price")).isEqualTo(1.0d);
        assertThat(counter("bricklink_pricing_apply_readiness_decision", "result", "skipped_blocked_reason_code")).isEqualTo(1.0d);
        assertThat(counter("bricklink_pricing_apply_readiness_decision", "result", "skipped_below_minimum_delta")).isEqualTo(1.0d);
        assertThat(counter("bricklink_pricing_apply_readiness_decision", "result", "skipped_below_minimum_delta_percent")).isEqualTo(1.0d);
        assertThat(counter("bricklink_pricing_apply_readiness_decision", "result", "skipped_above_maximum_percent_delta")).isEqualTo(1.0d);
        assertThat(counter("bricklink_pricing_apply_readiness_decision", "result", "skipped_stale_decision")).isEqualTo(1.0d);
        assertThat(gauge("bricklink_pricing_apply_readiness_current", "status", "ready_to_apply")).isEqualTo(7.0d);
        assertThat(gauge("bricklink_pricing_apply_readiness_current", "status", "blocked_missing_current_price")).isEqualTo(1.0d);
        assertThat(gauge("bricklink_pricing_apply_readiness_block_reason_current", "reason", "missing_current_price")).isEqualTo(1.0d);

        List<String> statusTags = List.of(
                "ready_to_apply",
                "ready_to_apply_initial_price",
                "blocked_fixed_price",
                "blocked_missing_current_price",
                "blocked_missing_final_price",
                "blocked_currency_mismatch",
                "blocked_unsupported_decision_status",
                "blocked_reason_code",
                "blocked_ineligible_reason",
                "blocked_below_minimum_delta",
                "blocked_below_minimum_delta_percent",
                "blocked_below_minimum_confidence",
                "blocked_below_minimum_comparable_count",
                "blocked_above_maximum_absolute_delta",
                "blocked_above_maximum_percent_delta",
                "blocked_stale_decision"
        );
        statusTags.forEach(status -> assertThat(meterRegistry.find("bricklink_pricing_apply_readiness_current")
                .tag("status", status)
                .gauge()).as(status).isNotNull());

        List<String> reasonTags = List.of(
                "fixed_price",
                "missing_current_price",
                "missing_final_price",
                "currency_mismatch",
                "unsupported_decision_status",
                "blocked_reason_code",
                "ineligible_reason",
                "below_minimum_delta",
                "below_minimum_delta_percent",
                "below_minimum_confidence",
                "below_minimum_comparable_count",
                "above_maximum_absolute_delta",
                "above_maximum_percent_delta",
                "stale_decision"
        );
        reasonTags.forEach(reason -> assertThat(meterRegistry.find("bricklink_pricing_apply_readiness_block_reason_current")
                .tag("reason", reason)
                .gauge()).as(reason).isNotNull());
    }

    @Test
    void recordsPricingApplyAndMarketplaceSyncMetrics() {
        when(marketplaceListingSyncRequestDao.countBySyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_PENDING)).thenReturn(4L);
        when(marketplaceListingSyncRequestDao.countDueBySyncRequestStatusCode(eq(BricklinkMarketplaceSyncService.STATUS_PENDING), any())).thenReturn(2L);
        when(marketplaceListingSyncRequestDao.countBySyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_CLAIMED)).thenReturn(1L);
        when(marketplaceListingSyncRequestDao.countBySyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_SUCCEEDED)).thenReturn(8L);
        when(marketplaceListingSyncRequestDao.countBySyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_BLOCKED)).thenReturn(3L);
        when(marketplaceListingSyncRequestDao.countBySyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_FAILED)).thenReturn(1L);

        metricsService.recordApply(new BricklinkPricingApplyResult(
                "SUCCESS",
                true,
                BricklinkPricingApplyMode.APPLY_LOCAL_AND_ENQUEUE_SYNC,
                5,
                4,
                4,
                0,
                1,
                0,
                100
        ));
        metricsService.recordMarketplaceSync(new BricklinkMarketplaceSyncResult(
                "PARTIAL_SUCCESS",
                BricklinkMarketplaceSyncMode.APPLY,
                5,
                5,
                3,
                3,
                0,
                1,
                1,
                125
        ));

        assertThat(counter("bricklink_pricing_apply_job", "outcome", "success")).isEqualTo(1.0d);
        assertThat(timerCount("bricklink_pricing_apply_job_duration", "outcome", "success")).isOne();
        assertThat(counter("bricklink_pricing_apply_decision", "result", "selected")).isEqualTo(5.0d);
        assertThat(counter("bricklink_pricing_apply_decision", "result", "local_price_updated")).isEqualTo(4.0d);
        assertThat(counter("bricklink_pricing_apply_decision", "result", "sync_request_enqueued")).isEqualTo(4.0d);
        assertThat(counter("bricklink_marketplace_sync_job", "outcome", "partial_success")).isEqualTo(1.0d);
        assertThat(timerCount("bricklink_marketplace_sync_job_duration", "outcome", "partial_success")).isOne();
        assertThat(counter("bricklink_marketplace_sync_request", "result", "selected")).isEqualTo(5.0d);
        assertThat(counter("bricklink_marketplace_sync_request", "result", "remote_updated")).isEqualTo(3.0d);
        assertThat(counter("bricklink_marketplace_sync_request", "result", "blocked")).isEqualTo(1.0d);
        assertThat(counter("bricklink_marketplace_sync_request", "result", "failed")).isEqualTo(1.0d);
        assertThat(gauge("bricklink_marketplace_sync_request_current", "state", "pending")).isEqualTo(4.0d);
        assertThat(gauge("bricklink_marketplace_sync_request_current", "state", "due")).isEqualTo(2.0d);
        assertThat(gauge("bricklink_marketplace_sync_request_current", "state", "claimed")).isEqualTo(1.0d);
        assertThat(gauge("bricklink_marketplace_sync_request_current", "state", "succeeded")).isEqualTo(8.0d);
        assertThat(gauge("bricklink_marketplace_sync_request_current", "state", "blocked")).isEqualTo(3.0d);
        assertThat(gauge("bricklink_marketplace_sync_request_current", "state", "failed")).isEqualTo(1.0d);
    }

    private double counter(String metricName, String tag, String value) {
        return meterRegistry.get(metricName).tag(tag, value).counter().count();
    }

    private double timerCount(String metricName, String tag, String value) {
        return meterRegistry.get(metricName).tag(tag, value).timer().count();
    }

    private double gauge(String metricName, String tag, String value) {
        return meterRegistry.get(metricName).tag(tag, value).gauge().value();
    }

    private double gauge(String metricName, String tag1, String value1, String tag2, String value2) {
        return meterRegistry.get(metricName).tag(tag1, value1).tag(tag2, value2).gauge().value();
    }
}
