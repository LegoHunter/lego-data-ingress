package io.legohunter.ingress.pricing;

import io.legohunter.data.dao.CrawlTargetDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.ingress.marketplace.bricklink.BricklinkMarketplaceSyncMode;
import io.legohunter.ingress.marketplace.bricklink.BricklinkMarketplaceSyncResult;
import io.legohunter.ingress.marketplace.bricklink.BricklinkMarketplaceSyncService;
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
    private final MarketplaceListingSyncRequestDao marketplaceListingSyncRequestDao = mock(MarketplaceListingSyncRequestDao.class);
    private final BricklinkPricingMetricsService metricsService = new BricklinkPricingMetricsService(
            meterRegistry,
            crawlTargetDao,
            pricingDecisionDao,
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
    void registersTheLatestDecisionGaugesOnTheFirstRun() {
        when(pricingDecisionDao.countLatestByDecisionStatusCode(PricingDecisionStatus.PROPOSED)).thenReturn(8L);
        when(pricingDecisionDao.countLatestByDecisionStatusCode(PricingDecisionStatus.FAILED)).thenReturn(2L);
        when(pricingDecisionDao.countLatestByDecisionStatusCode(PricingDecisionStatus.SKIPPED)).thenReturn(1L);
        when(pricingDecisionDao.countLatestUnappliedByDecisionStatusCode(PricingDecisionStatus.PROPOSED)).thenReturn(6L);

        when(pricingDecisionDao.countLatestByDecisionStatusCode(PricingDecisionStatus.BLOCKED)).thenReturn(3L);

        metricsService.recordReprice(RepriceResult.noWork(RepriceMode.APPLY, 5));

        assertThat(gauge("bricklink_pricing_decision_current", "status", "proposed", "unapplied_only", "false")).isEqualTo(8.0d);
        assertThat(gauge("bricklink_pricing_decision_current", "status", "failed", "unapplied_only", "false")).isEqualTo(2.0d);
        assertThat(gauge("bricklink_pricing_decision_current", "status", "skipped", "unapplied_only", "false")).isEqualTo(1.0d);
        assertThat(gauge("bricklink_pricing_decision_current", "status", "proposed", "unapplied_only", "true")).isEqualTo(6.0d);
        assertThat(gauge("bricklink_pricing_decision_current", "status", "blocked", "unapplied_only", "false")).isEqualTo(3.0d);
    }


    @Test
    void recordsMarketplaceSyncMetrics() {
        when(marketplaceListingSyncRequestDao.countBySyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_PENDING)).thenReturn(4L);
        when(marketplaceListingSyncRequestDao.countDueBySyncRequestStatusCode(eq(BricklinkMarketplaceSyncService.STATUS_PENDING), any())).thenReturn(2L);
        when(marketplaceListingSyncRequestDao.countBySyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_CLAIMED)).thenReturn(1L);
        when(marketplaceListingSyncRequestDao.countBySyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_SUCCEEDED)).thenReturn(8L);
        when(marketplaceListingSyncRequestDao.countBySyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_BLOCKED)).thenReturn(3L);
        when(marketplaceListingSyncRequestDao.countBySyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_FAILED)).thenReturn(1L);

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

    @Test
    void registersRepriceCountersAtZeroSoTheFirstEventShowsAsAnIncrease() {
        metricsService.recordReprice(RepriceResult.noWork(RepriceMode.DRY_RUN, 5));

        assertThat(counter("pricing_reprice_job", "outcome", "no_work")).isEqualTo(1.0d);
        assertThat(counter("pricing_reprice_decision", "result", "proposed")).isZero();
        assertThat(counter("pricing_reprice_decision", "result", "blocked")).isZero();
        assertThat(counter("pricing_reprice_listing", "result", "selected")).isZero();
        assertThat(counter("pricing_reprice_sync", "result", "handed_off")).isZero();

        metricsService.recordReprice(new RepriceResult("SUCCESS", RepriceMode.DRY_RUN,
                3, 3, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 40));

        assertThat(counter("pricing_reprice_decision", "result", "proposed")).isEqualTo(2.0d);
        assertThat(counter("pricing_reprice_decision", "result", "blocked")).isEqualTo(1.0d);
        assertThat(counter("pricing_reprice_listing", "result", "selected")).isEqualTo(3.0d);
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
