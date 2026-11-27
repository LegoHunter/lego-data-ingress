package io.legohunter.ingress.source.lego.woodentoys.capture;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The monthly capture of LEGO's wooden toys catalog. It starts the run in the background and returns, so a long
 * capture does not hold the scheduler's thread, which the other jobs share. The lock is therefore held only for
 * {@code lock-at-least-for}: long enough that a second instance does not start the same month's run.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = LegoWoodenToysCaptureProperties.PREFIX,
        name = {"enabled", "scheduled.enabled"},
        havingValue = "true"
)
public class LegoWoodenToysCaptureJob {
    private final LegoWoodenToysCaptureRunner runner;

    @Scheduled(cron = "${lego.catalog-capture.lego-wooden-toys.scheduled.cron:0 15 4 2 * *}")
    @SchedulerLock(
            name = "LegoWoodenToysCaptureJob",
            lockAtMostFor = "${lego.catalog-capture.lego-wooden-toys.scheduled.lock-at-most-for:15m}",
            lockAtLeastFor = "${lego.catalog-capture.lego-wooden-toys.scheduled.lock-at-least-for:10m}"
    )
    public void runScheduled() {
        runner.start(true, false);
    }
}
