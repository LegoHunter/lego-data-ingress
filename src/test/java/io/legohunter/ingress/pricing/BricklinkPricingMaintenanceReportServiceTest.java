package io.legohunter.ingress.pricing;

import io.legohunter.data.dao.CrawlAttemptDao;
import io.legohunter.data.dao.CrawlTargetDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dto.CrawlAttempt;
import io.legohunter.data.dto.PricingDataQualityGap;
import io.legohunter.data.dto.PricingDataQualitySummary;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingMaintenanceReportServiceTest {
    @Test
    void buildReportSummarisesTheSharedCrawlQueueAndRecentFailures() {
        CrawlTargetDao crawlTargetDao = mock(CrawlTargetDao.class);
        CrawlAttemptDao crawlAttemptDao = mock(CrawlAttemptDao.class);
        when(crawlTargetDao.countByTargetStatusCode("BRICKLINK", "IDLE")).thenReturn(10L);
        when(crawlTargetDao.countByTargetStatusCode("BRICKLINK", "CLAIMED")).thenReturn(1L);
        when(crawlTargetDao.countByTargetStatusCode("BRICKLINK", "DISABLED")).thenReturn(2L);
        when(crawlTargetDao.countDue(eq("BRICKLINK"), any())).thenReturn(3L);
        when(crawlTargetDao.countFailing("BRICKLINK")).thenReturn(4L);
        CrawlAttempt failure = CrawlAttempt.builder().crawlAttemptId(300L).outcomeCode("FAILED_HTTP").errorMessage("maintenance").build();
        when(crawlAttemptDao.findRecentFailures("BRICKLINK", 1)).thenReturn(List.of(failure));

        BricklinkPricingMaintenanceReportService service = new BricklinkPricingMaintenanceReportService(
                crawlTargetDao,
                crawlAttemptDao,
                mock(MarketplaceListingDao.class),
                new BricklinkPricingCrawlProperties()
        );

        BricklinkPricingMaintenanceReport report = service.buildReport(0);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.generatedAt()).isNotNull();
        assertThat(report.crawlTargets()).isEqualTo(new BricklinkPricingMaintenanceReport.CrawlTargetSummary(10, 1, 2, 3, 4));
        assertThat(report.recentFailures()).containsExactly(failure);
    }

    @Test
    void buildDataQualityReportUsesPriceableStatusesAndCapsTheLimit() {
        MarketplaceListingDao marketplaceListingDao = mock(MarketplaceListingDao.class);
        BricklinkPricingCrawlProperties properties = new BricklinkPricingCrawlProperties();
        properties.setBricklinkExternalServiceId(7);
        properties.setPriceableListingStatusCodes(Set.of("active", "draft"));

        PricingDataQualitySummary summary = PricingDataQualitySummary.builder()
                .listingsChecked(12)
                .listingsWithGaps(1)
                .missingBoxGrade(1)
                .build();
        PricingDataQualityGap gap = PricingDataQualityGap.builder()
                .marketplaceListingId(42)
                .newOrUsed("U")
                .missingBoxGrade(true)
                .build();
        when(marketplaceListingDao.summarizePricingDataQualityByListingExternalServiceIdAndListingStatusCodes(7, Set.of("ACTIVE", "DRAFT")))
                .thenReturn(summary);
        when(marketplaceListingDao.findPricingDataQualityGapsByListingExternalServiceIdAndListingStatusCodes(
                7,
                Set.of("ACTIVE", "DRAFT"),
                BricklinkPricingMaintenanceReportService.MAX_REPORT_LIMIT
        )).thenReturn(Set.of(gap));

        BricklinkPricingMaintenanceReportService service = new BricklinkPricingMaintenanceReportService(
                mock(CrawlTargetDao.class),
                mock(CrawlAttemptDao.class),
                marketplaceListingDao,
                properties
        );

        BricklinkPricingDataQualityReport report = service.buildDataQualityReport(10_000);

        assertThat(report.generatedAt()).isNotNull();
        assertThat(report.listingStatusCodes()).containsExactlyInAnyOrder("ACTIVE", "DRAFT");
        assertThat(report.limit()).isEqualTo(BricklinkPricingMaintenanceReportService.MAX_REPORT_LIMIT);
        assertThat(report.summary()).isSameAs(summary);
        assertThat(report.gaps()).containsExactly(gap);
    }

    @Test
    void buildReportCapsLargeLimits() {
        CrawlAttemptDao crawlAttemptDao = mock(CrawlAttemptDao.class);
        when(crawlAttemptDao.findRecentFailures("BRICKLINK", BricklinkPricingMaintenanceReportService.MAX_REPORT_LIMIT))
                .thenReturn(List.of());
        BricklinkPricingMaintenanceReportService service = new BricklinkPricingMaintenanceReportService(
                mock(CrawlTargetDao.class),
                crawlAttemptDao,
                mock(MarketplaceListingDao.class),
                new BricklinkPricingCrawlProperties()
        );

        service.buildReport(10_000);

        verify(crawlAttemptDao).findRecentFailures("BRICKLINK", BricklinkPricingMaintenanceReportService.MAX_REPORT_LIMIT);
    }
}
