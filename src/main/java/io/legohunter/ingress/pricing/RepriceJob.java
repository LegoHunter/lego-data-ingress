package io.legohunter.ingress.pricing;

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
        prefix = "lego.pricing.reprice",
        name = {"enabled", "scheduled.enabled"},
        havingValue = "true"
)
public class RepriceJob {
    private final RepriceService repriceService;
    private final BricklinkPricingMetricsService metricsService;

    @Scheduled(
            fixedDelayString = "${lego.pricing.reprice.scheduled.fixed-delay-ms:300000}",
            initialDelayString = "${lego.pricing.reprice.scheduled.initial-delay-ms:60000}"
    )
    @SchedulerLock(
            name = "RepriceJob",
            lockAtMostFor = "${lego.pricing.reprice.scheduled.lock-at-most-for:10m}",
            lockAtLeastFor = "${lego.pricing.reprice.scheduled.lock-at-least-for:0s}"
    )
    public void runScheduled() {
        runOnce();
    }

    RepriceResult runOnce() {
        RepriceResult result = repriceService.runOnce();
        metricsService.recordReprice(result);
        log.info("pricing.reprice.job.completed result={}", result);
        return result;
    }
}
