package io.legohunter.ingress.orders;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.orders.sync.scheduled", name = "enabled", havingValue = "true")
public class BricklinkOpenOrderProbeJob {
    private final BricklinkOpenOrderProbeService probeService;

    @Scheduled(
            fixedDelayString = "${lego.bricklink.orders.sync.scheduled.fixed-delay-ms:3600000}",
            initialDelayString = "${lego.bricklink.orders.sync.scheduled.initial-delay-ms:30000}"
    )
    @SchedulerLock(
            name = "BricklinkOpenOrderProbeJob",
            lockAtMostFor = "${lego.bricklink.orders.sync.scheduled.lock-at-most-for:10m}",
            lockAtLeastFor = "${lego.bricklink.orders.sync.scheduled.lock-at-least-for:0s}"
    )
    public void runScheduled() {
        runOnce();
    }

    BricklinkOrderProbeResult runOnce() {
        return probeService.runOnce();
    }
}
