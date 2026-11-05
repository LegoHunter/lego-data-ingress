package io.legohunter.ingress.source.bricklink.pricing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        prefix = "lego.bricklink.pricing.crawl",
        name = {"enabled", "scheduled.enabled"},
        havingValue = "true"
)
public class BricklinkPricingCrawlJob {
    private final BricklinkPricingCrawlService crawlService;
    private final BricklinkPricingMetricsService metricsService;
    private final BricklinkPricingDataQualityMetrics dataQualityMetrics;

    @Scheduled(
            fixedDelayString = "${lego.bricklink.pricing.crawl.scheduled.fixed-delay-ms:300000}",
            initialDelayString = "${lego.bricklink.pricing.crawl.scheduled.initial-delay-ms:30000}"
    )
    @SchedulerLock(
            name = "BricklinkPricingCrawlJob",
            lockAtMostFor = "${lego.bricklink.pricing.crawl.scheduled.lock-at-most-for:30m}",
            lockAtLeastFor = "${lego.bricklink.pricing.crawl.scheduled.lock-at-least-for:0s}"
    )
    public void runScheduled() {
        runOnce();
    }

    BricklinkPricingCrawlResult runOnce() {
        BricklinkPricingCrawlResult result = crawlService.runOnce();
        metricsService.recordCrawl(result);
        dataQualityMetrics.refresh();
        log.info("bricklink.pricing.crawl.job.completed result={}", result);
        return result;
    }
}
