package io.legohunter.ingress.marketplace.bricklink;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the Extended Description sync on a schedule and records what it did. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "lego.bricklink.extended-description-sync",
        name = {"enabled", "scheduled.enabled"},
        havingValue = "true"
)
public class BricklinkExtendedDescriptionSyncJob {
    static final String JOB = "bricklink_extended_description_sync_job";
    static final String TEXTS = "bricklink_extended_description_sync_texts";

    private final BricklinkExtendedDescriptionSyncService extendedDescriptionSyncService;
    private final MeterRegistry meterRegistry;

    @Scheduled(
            fixedDelayString = "${lego.bricklink.extended-description-sync.scheduled.fixed-delay-ms:600000}",
            initialDelayString = "${lego.bricklink.extended-description-sync.scheduled.initial-delay-ms:150000}"
    )
    @SchedulerLock(
            name = "BricklinkExtendedDescriptionSyncJob",
            lockAtMostFor = "${lego.bricklink.extended-description-sync.scheduled.lock-at-most-for:20m}",
            lockAtLeastFor = "${lego.bricklink.extended-description-sync.scheduled.lock-at-least-for:0s}"
    )
    public void runScheduled() {
        runOnce();
    }

    BricklinkExtendedDescriptionSyncResult runOnce() {
        BricklinkExtendedDescriptionSyncResult result;
        try {
            result = extendedDescriptionSyncService.runOnce();
        } catch (RuntimeException e) {
            meterRegistry.counter(JOB, "mode", "unknown", "outcome", "ERROR").increment();
            log.error("bricklink.extended_description.job.failed message={}", e.getMessage(), e);
            throw e;
        }
        String mode = result.mode().name().toLowerCase();
        meterRegistry.counter(JOB, "mode", mode, "outcome", result.outcome()).increment();
        count(mode, "stored", result.stored());
        count(mode, "blocked", result.blocked());
        count(mode, "failed", result.failed());
        count(mode, "deferred", result.deferred());
        count(mode, "dry_run_verified", result.dryRunVerified());
        log.info("bricklink.extended_description.job.completed result={}", result);
        return result;
    }

    private void count(String mode, String outcome, int amount) {
        if (amount > 0) {
            meterRegistry.counter(TEXTS, "mode", mode, "outcome", outcome).increment(amount);
        }
    }
}
