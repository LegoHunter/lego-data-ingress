package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.PricingCrawlWorkItemDao;
import io.legohunter.data.dto.PricingCrawlWorkItemDuplicate;
import io.legohunter.data.dto.PricingCrawlWorkItemFailure;
import io.legohunter.data.dto.PricingCrawlWorkItemMaintenanceSummary;
import io.legohunter.data.dto.PricingDataQualityGap;
import io.legohunter.data.dto.PricingDataQualitySummary;
import io.legohunter.data.dto.PricingHydrationGap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BricklinkPricingMaintenanceReportService {
    static final int MAX_REPORT_LIMIT = 500;

    private final PricingCrawlWorkItemDao pricingCrawlWorkItemDao;
    private final MarketplaceListingDao marketplaceListingDao;
    private final BricklinkPricingCrawlProperties crawlProperties;

    public BricklinkPricingMaintenanceReport buildReport(int limit) {
        int effectiveLimit = Math.min(MAX_REPORT_LIMIT, Math.max(1, limit));
        ZonedDateTime generatedAt = now();
        PricingCrawlWorkItemMaintenanceSummary summary = pricingCrawlWorkItemDao.summarizeMaintenance(
                BricklinkPricingCrawlService.STATUS_PENDING,
                BricklinkPricingCrawlService.STATUS_CLAIMED,
                BricklinkPricingCrawlService.STATUS_SUCCEEDED,
                generatedAt,
                generatedAt.minus(crawlProperties.effectiveClaimStaleAfter())
        );
        Set<PricingCrawlWorkItemDuplicate> duplicateWorkItems = pricingCrawlWorkItemDao.findDuplicateMarketplaceListingWorkItems(
                BricklinkPricingCrawlService.STATUS_PENDING,
                BricklinkPricingCrawlService.STATUS_CLAIMED,
                effectiveLimit
        );
        Set<PricingCrawlWorkItemFailure> recentFailures = pricingCrawlWorkItemDao.findRecentFailures(effectiveLimit);
        Set<PricingHydrationGap> hydrationGaps = marketplaceListingDao.findPricingHydrationGapsByListingExternalServiceIdAndListingStatusCode(
                crawlProperties.getBricklinkExternalServiceId(),
                crawlProperties.effectiveActiveListingStatusCode(),
                effectiveLimit
        );
        return new BricklinkPricingMaintenanceReport(generatedAt, true, summary, duplicateWorkItems, recentFailures, hydrationGaps);
    }

    public BricklinkPricingDataQualityReport buildDataQualityReport(int limit) {
        int effectiveLimit = Math.min(MAX_REPORT_LIMIT, Math.max(1, limit));
        Integer serviceId = crawlProperties.getBricklinkExternalServiceId();
        Set<String> statusCodes = crawlProperties.effectivePriceableListingStatusCodes();
        PricingDataQualitySummary summary = marketplaceListingDao.summarizePricingDataQualityByListingExternalServiceIdAndListingStatusCodes(
                serviceId,
                statusCodes
        );
        Set<PricingDataQualityGap> gaps = marketplaceListingDao.findPricingDataQualityGapsByListingExternalServiceIdAndListingStatusCodes(
                serviceId,
                statusCodes,
                effectiveLimit
        );
        return new BricklinkPricingDataQualityReport(now(), statusCodes, effectiveLimit, summary, gaps);
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }
}
