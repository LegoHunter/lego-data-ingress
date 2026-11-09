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
        prefix = "lego.bricklink.pricing.apply",
        name = {"enabled", "scheduled.enabled"},
        havingValue = "true"
)
public class BricklinkPricingApplyJob {
    private final BricklinkPricingApplyService applyService;
    private final BricklinkPricingMetricsService metricsService;

    @Scheduled(
            fixedDelayString = "${lego.bricklink.pricing.apply.scheduled.fixed-delay-ms:300000}",
            initialDelayString = "${lego.bricklink.pricing.apply.scheduled.initial-delay-ms:30000}"
    )
    @SchedulerLock(
            name = "BricklinkPricingApplyJob",
            lockAtMostFor = "${lego.bricklink.pricing.apply.scheduled.lock-at-most-for:10m}",
            lockAtLeastFor = "${lego.bricklink.pricing.apply.scheduled.lock-at-least-for:0s}"
    )
    public void runScheduled() {
        runOnce();
    }

    BricklinkPricingApplyResult runOnce() {
        BricklinkPricingApplyResult result = applyService.runOnce();
        metricsService.recordApply(result);
        log.info("bricklink.pricing.apply.job.completed result={}", result);
        return result;
    }
}
