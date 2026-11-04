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
        prefix = "lego.bricklink.inventory-reconciliation",
        name = {"enabled", "scheduled.enabled"},
        havingValue = "true"
)
public class BricklinkInventoryReconciliationJob {
    private final BricklinkInventoryReconciliationService reconciliationService;
    private final BricklinkInventoryReconciliationMetrics metrics;

    @Scheduled(
            fixedDelayString = "${lego.bricklink.inventory-reconciliation.scheduled.fixed-delay-ms:3600000}",
            initialDelayString = "${lego.bricklink.inventory-reconciliation.scheduled.initial-delay-ms:120000}"
    )
    @SchedulerLock(
            name = "BricklinkInventoryReconciliationJob",
            lockAtMostFor = "${lego.bricklink.inventory-reconciliation.scheduled.lock-at-most-for:10m}",
            lockAtLeastFor = "${lego.bricklink.inventory-reconciliation.scheduled.lock-at-least-for:0s}"
    )
    public void runScheduled() {
        runOnce();
    }

    BricklinkInventoryReconciliationReport runOnce() {
        BricklinkInventoryReconciliationReport report = reconciliationService.reconcile();
        metrics.record(report);
        return report;
    }
}
