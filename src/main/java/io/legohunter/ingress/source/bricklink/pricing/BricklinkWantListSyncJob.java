package io.legohunter.ingress.source.bricklink.pricing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the want list sync on a schedule in every environment that enables it (HOMELAB-32). */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        prefix = "lego.bricklink.pricing.want-list",
        name = {"enabled", "scheduled.enabled"},
        havingValue = "true"
)
public class BricklinkWantListSyncJob {
    private final BricklinkWantListSyncService wantListSyncService;
    private final BricklinkPricingMetricsService metricsService;

    @Scheduled(
            fixedDelayString = "${lego.bricklink.pricing.want-list.scheduled.fixed-delay-ms:3600000}",
            initialDelayString = "${lego.bricklink.pricing.want-list.scheduled.initial-delay-ms:60000}"
    )
    @SchedulerLock(
            name = "BricklinkWantListSyncJob",
            lockAtMostFor = "${lego.bricklink.pricing.want-list.scheduled.lock-at-most-for:10m}",
            lockAtLeastFor = "${lego.bricklink.pricing.want-list.scheduled.lock-at-least-for:0s}"
    )
    public void runScheduled() {
        runOnce();
    }

    BricklinkWantListSyncResult runOnce() {
        BricklinkWantListSyncResult result = wantListSyncService.runOnce();
        metricsService.recordWantList(result);
        log.info("bricklink.pricing.want_list.job.completed result={}", result);
        return result;
    }
}
