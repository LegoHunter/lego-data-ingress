package io.legohunter.ingress.source.bricklink.audit;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "lego.bricklink.lot-audit",
        name = {"enabled", "scheduled.enabled"},
        havingValue = "true"
)
public class BricklinkLotAuditJob {
    private final BricklinkLotAuditService auditService;
    private final BricklinkLotAuditMetrics metrics;

    @Scheduled(
            fixedDelayString = "${lego.bricklink.lot-audit.scheduled.fixed-delay-ms:1800000}",
            initialDelayString = "${lego.bricklink.lot-audit.scheduled.initial-delay-ms:60000}"
    )
    @SchedulerLock(
            name = "BricklinkLotAuditJob",
            lockAtMostFor = "${lego.bricklink.lot-audit.scheduled.lock-at-most-for:10m}",
            lockAtLeastFor = "${lego.bricklink.lot-audit.scheduled.lock-at-least-for:0s}"
    )
    public void runScheduled() {
        runOnce();
    }

    BricklinkLotAuditReport runOnce() {
        BricklinkLotAuditReport report = auditService.audit();
        metrics.record(report);
        return report;
    }
}
