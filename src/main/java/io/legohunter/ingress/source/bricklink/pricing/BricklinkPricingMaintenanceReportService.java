package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.CrawlAttemptDao;
import io.legohunter.data.dao.CrawlTargetDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dto.CrawlAttempt;
import io.legohunter.data.dto.PricingDataQualityGap;
import io.legohunter.data.dto.PricingDataQualitySummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BricklinkPricingMaintenanceReportService {
    static final int MAX_REPORT_LIMIT = 500;

    private final CrawlTargetDao crawlTargetDao;
    private final CrawlAttemptDao crawlAttemptDao;
    private final MarketplaceListingDao marketplaceListingDao;
    private final BricklinkPricingCrawlProperties crawlProperties;

    /**
     * The shared BrickLink crawl queue and its latest failures (HOMELAB-32). The queue is shared by every
     * environment, so every deployment reports the same numbers.
     */
    public BricklinkPricingMaintenanceReport buildReport(int limit) {
        int effectiveLimit = Math.min(MAX_REPORT_LIMIT, Math.max(1, limit));
        ZonedDateTime generatedAt = now();
        String marketplace = BricklinkPricingCrawlService.MARKETPLACE_CODE;
        BricklinkPricingMaintenanceReport.CrawlTargetSummary summary = new BricklinkPricingMaintenanceReport.CrawlTargetSummary(
                crawlTargetDao.countByTargetStatusCode(marketplace, "IDLE"),
                crawlTargetDao.countByTargetStatusCode(marketplace, "CLAIMED"),
                crawlTargetDao.countByTargetStatusCode(marketplace, "DISABLED"),
                crawlTargetDao.countDue(marketplace, generatedAt),
                crawlTargetDao.countFailing(marketplace)
        );
        List<CrawlAttempt> recentFailures = crawlAttemptDao.findRecentFailures(marketplace, effectiveLimit);
        return new BricklinkPricingMaintenanceReport(generatedAt, true, summary, recentFailures);
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
