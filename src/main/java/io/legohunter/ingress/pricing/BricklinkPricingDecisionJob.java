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
        prefix = "lego.bricklink.pricing.decision",
        name = {"enabled", "scheduled.enabled"},
        havingValue = "true"
)
public class BricklinkPricingDecisionJob {
    private final BricklinkPricingDecisionService decisionService;
    private final BricklinkPricingMetricsService metricsService;

    @Scheduled(
            fixedDelayString = "${lego.bricklink.pricing.decision.scheduled.fixed-delay-ms:300000}",
            initialDelayString = "${lego.bricklink.pricing.decision.scheduled.initial-delay-ms:30000}"
    )
    @SchedulerLock(
            name = "BricklinkPricingDecisionJob",
            lockAtMostFor = "${lego.bricklink.pricing.decision.scheduled.lock-at-most-for:10m}",
            lockAtLeastFor = "${lego.bricklink.pricing.decision.scheduled.lock-at-least-for:0s}"
    )
    public void runScheduled() {
        runOnce();
    }

    BricklinkPricingDecisionResult runOnce() {
        BricklinkPricingDecisionResult result = decisionService.runOnce();
        metricsService.recordDecision(result);
        log.info("bricklink.pricing.decision.job.completed result={}", result);
        return result;
    }
}
