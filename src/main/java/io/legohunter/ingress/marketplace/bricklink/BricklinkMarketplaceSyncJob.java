package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.ingress.pricing.BricklinkPricingMetricsService;
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
        prefix = "lego.bricklink.marketplace-sync",
        name = {"enabled", "scheduled.enabled"},
        havingValue = "true"
)
public class BricklinkMarketplaceSyncJob {
    private final BricklinkMarketplaceSyncService marketplaceSyncService;
    private final BricklinkPricingMetricsService metricsService;

    @Scheduled(
            fixedDelayString = "${lego.bricklink.marketplace-sync.scheduled.fixed-delay-ms:300000}",
            initialDelayString = "${lego.bricklink.marketplace-sync.scheduled.initial-delay-ms:30000}"
    )
    @SchedulerLock(
            name = "BricklinkMarketplaceSyncJob",
            lockAtMostFor = "${lego.bricklink.marketplace-sync.scheduled.lock-at-most-for:10m}",
            lockAtLeastFor = "${lego.bricklink.marketplace-sync.scheduled.lock-at-least-for:0s}"
    )
    public void runScheduled() {
        runOnce();
    }

    BricklinkMarketplaceSyncResult runOnce() {
        BricklinkMarketplaceSyncResult result = marketplaceSyncService.runOnce();
        metricsService.recordMarketplaceSync(result);
        log.info("bricklink.marketplace_sync.job.completed result={}", result);
        return result;
    }
}
