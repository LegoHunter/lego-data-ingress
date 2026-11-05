package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.PricingCrawlWorkItemDao;
import io.legohunter.data.dto.PricingCrawlWorkItemDuplicate;
import io.legohunter.data.dto.PricingCrawlWorkItemFailure;
import io.legohunter.data.dto.PricingCrawlWorkItemMaintenanceSummary;
import io.legohunter.data.dto.PricingDataQualityGap;
import io.legohunter.data.dto.PricingDataQualitySummary;
import io.legohunter.data.dto.PricingHydrationGap;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingMaintenanceReportServiceTest {
    @Test
    void buildReportReturnsDryRunQueueAndHydrationDiagnostics() {
        PricingCrawlWorkItemDao workItemDao = mock(PricingCrawlWorkItemDao.class);
        MarketplaceListingDao marketplaceListingDao = mock(MarketplaceListingDao.class);
        BricklinkPricingCrawlProperties properties = new BricklinkPricingCrawlProperties();
        properties.setBricklinkExternalServiceId(7);
        properties.setActiveListingStatusCode("active");
        properties.setClaimStaleAfter(Duration.ofHours(3));

        PricingCrawlWorkItemMaintenanceSummary summary = PricingCrawlWorkItemMaintenanceSummary.builder()
                .workItemCount(10L)
                .duplicatePendingWorkItemCount(1L)
                .build();
        PricingCrawlWorkItemDuplicate duplicate = PricingCrawlWorkItemDuplicate.builder()
                .marketplaceListingId(100)
                .workItemCount(2)
                .pendingCount(1)
                .build();
        PricingCrawlWorkItemFailure failure = PricingCrawlWorkItemFailure.builder()
                .pricingCrawlWorkItemId(300L)
                .lastErrorMessage("returned []")
                .build();
        PricingHydrationGap hydrationGap = PricingHydrationGap.builder()
                .marketplaceListingId(200)
                .externalItemKey("6390-1")
                .build();

        when(workItemDao.summarizeMaintenance(eq("PENDING"), eq("CLAIMED"), eq("SUCCEEDED"), any(), any()))
                .thenReturn(summary);
        when(workItemDao.findDuplicateMarketplaceListingWorkItems("PENDING", "CLAIMED", 1)).thenReturn(Set.of(duplicate));
        when(workItemDao.findRecentFailures(1)).thenReturn(Set.of(failure));
        when(marketplaceListingDao.findPricingHydrationGapsByListingExternalServiceIdAndListingStatusCode(7, "ACTIVE", 1))
                .thenReturn(Set.of(hydrationGap));

        BricklinkPricingMaintenanceReportService service = new BricklinkPricingMaintenanceReportService(
                workItemDao,
                marketplaceListingDao,
                properties
        );

        BricklinkPricingMaintenanceReport report = service.buildReport(0);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.generatedAt()).isNotNull();
        assertThat(report.workItemSummary()).isSameAs(summary);
        assertThat(report.duplicateWorkItems()).containsExactly(duplicate);
        assertThat(report.recentFailures()).containsExactly(failure);
        assertThat(report.hydrationGaps()).containsExactly(hydrationGap);
        verify(workItemDao).summarizeMaintenance(eq("PENDING"), eq("CLAIMED"), eq("SUCCEEDED"), any(), any());
        verify(workItemDao).findDuplicateMarketplaceListingWorkItems("PENDING", "CLAIMED", 1);
        verify(workItemDao).findRecentFailures(1);
        verify(marketplaceListingDao).findPricingHydrationGapsByListingExternalServiceIdAndListingStatusCode(7, "ACTIVE", 1);
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
                mock(PricingCrawlWorkItemDao.class),
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
        PricingCrawlWorkItemDao workItemDao = mock(PricingCrawlWorkItemDao.class);
        MarketplaceListingDao marketplaceListingDao = mock(MarketplaceListingDao.class);
        BricklinkPricingCrawlProperties properties = new BricklinkPricingCrawlProperties();
        properties.setBricklinkExternalServiceId(7);
        properties.setActiveListingStatusCode("ACTIVE");

        when(workItemDao.findDuplicateMarketplaceListingWorkItems("PENDING", "CLAIMED", BricklinkPricingMaintenanceReportService.MAX_REPORT_LIMIT))
                .thenReturn(Set.of());
        when(workItemDao.findRecentFailures(BricklinkPricingMaintenanceReportService.MAX_REPORT_LIMIT)).thenReturn(Set.of());
        when(marketplaceListingDao.findPricingHydrationGapsByListingExternalServiceIdAndListingStatusCode(
                7,
                "ACTIVE",
                BricklinkPricingMaintenanceReportService.MAX_REPORT_LIMIT
        )).thenReturn(Set.of());

        BricklinkPricingMaintenanceReportService service = new BricklinkPricingMaintenanceReportService(
                workItemDao,
                marketplaceListingDao,
                properties
        );

        service.buildReport(10_000);

        verify(workItemDao).findDuplicateMarketplaceListingWorkItems("PENDING", "CLAIMED", BricklinkPricingMaintenanceReportService.MAX_REPORT_LIMIT);
        verify(workItemDao).findRecentFailures(BricklinkPricingMaintenanceReportService.MAX_REPORT_LIMIT);
        verify(marketplaceListingDao).findPricingHydrationGapsByListingExternalServiceIdAndListingStatusCode(
                7,
                "ACTIVE",
                BricklinkPricingMaintenanceReportService.MAX_REPORT_LIMIT
        );
    }
}
