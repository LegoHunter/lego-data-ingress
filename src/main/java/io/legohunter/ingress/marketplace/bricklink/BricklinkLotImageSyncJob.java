package io.legohunter.ingress.marketplace.bricklink;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the lot image sync on a schedule and records what it did. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "lego.bricklink.lot-image-sync",
        name = {"enabled", "scheduled.enabled"},
        havingValue = "true"
)
public class BricklinkLotImageSyncJob {
    static final String JOB = "bricklink_lot_image_sync_job";
    static final String IMAGES = "bricklink_lot_image_sync_images";

    private final BricklinkLotImageSyncService lotImageSyncService;
    private final MeterRegistry meterRegistry;

    @Scheduled(
            fixedDelayString = "${lego.bricklink.lot-image-sync.scheduled.fixed-delay-ms:600000}",
            initialDelayString = "${lego.bricklink.lot-image-sync.scheduled.initial-delay-ms:90000}"
    )
    @SchedulerLock(
            name = "BricklinkLotImageSyncJob",
            lockAtMostFor = "${lego.bricklink.lot-image-sync.scheduled.lock-at-most-for:20m}",
            lockAtLeastFor = "${lego.bricklink.lot-image-sync.scheduled.lock-at-least-for:0s}"
    )
    public void runScheduled() {
        runOnce();
    }

    BricklinkLotImageSyncResult runOnce() {
        BricklinkLotImageSyncResult result;
        try {
            result = lotImageSyncService.runOnce();
        } catch (RuntimeException e) {
            meterRegistry.counter(JOB, "mode", "unknown", "outcome", "ERROR").increment();
            log.error("bricklink.lot_image.job.failed message={}", e.getMessage(), e);
            throw e;
        }
        String mode = result.mode().name().toLowerCase();
        meterRegistry.counter(JOB, "mode", mode, "outcome", result.outcome()).increment();
        count(mode, "stored", result.stored());
        count(mode, "blocked", result.blocked());
        count(mode, "failed", result.failed());
        count(mode, "deferred", result.deferred());
        count(mode, "dry_run_verified", result.dryRunVerified());
        count(mode, "multiple_primary_photos", result.multiplePrimaries());
        log.info("bricklink.lot_image.job.completed result={}", result);
        return result;
    }

    private void count(String mode, String outcome, int amount) {
        if (amount > 0) {
            meterRegistry.counter(IMAGES, "mode", mode, "outcome", outcome).increment(amount);
        }
    }
}
