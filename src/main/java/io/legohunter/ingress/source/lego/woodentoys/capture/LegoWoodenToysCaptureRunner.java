package io.legohunter.ingress.source.lego.woodentoys.capture;

import io.legohunter.ingress.source.capture.CatalogCaptureMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the capture one run at a time in this instance, records its metrics and keeps the last result. The
 * scheduled job runs through {@link #run}; the on-demand endpoint starts a run in the background through
 * {@link #start}, because a first capture downloads every picture and takes far longer than a request should.
 */
@Slf4j
public class LegoWoodenToysCaptureRunner {
    public static final String METRIC_SOURCE = "lego_wooden_toys";
    static final String PICTURE_PROBLEMS = "picture_problems";
    static final String SUSPECT_SHARED_PICTURES = "suspect_shared_pictures";

    private final LegoWoodenToysCaptureService captureService;
    private final CatalogCaptureMetrics metrics;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<LegoWoodenToysCaptureResult> lastResult = new AtomicReference<>();

    public LegoWoodenToysCaptureRunner(LegoWoodenToysCaptureService captureService, CatalogCaptureMetrics metrics,
                                       Executor executor) {
        this.captureService = captureService;
        this.metrics = metrics;
        this.executor = executor;
        metrics.register(METRIC_SOURCE, PICTURE_PROBLEMS, SUSPECT_SHARED_PICTURES);
    }

    /** Runs a capture and waits for it. Empty when a run is already under way. */
    public Optional<LegoWoodenToysCaptureResult> run(boolean apply, boolean force) {
        if (!running.compareAndSet(false, true)) {
            log.warn("lego_wooden_toys.capture.already_running");
            return Optional.empty();
        }
        return Optional.of(runHoldingTheFlag(apply, force));
    }

    /** Starts a capture in the background. False when a run is already under way. */
    public boolean start(boolean apply, boolean force) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> runHoldingTheFlag(apply, force));
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        return true;
    }

    public Status status() {
        return new Status(running.get(), lastResult.get());
    }

    private LegoWoodenToysCaptureResult runHoldingTheFlag(boolean apply, boolean force) {
        try {
            LegoWoodenToysCaptureResult result = captureService.capture(apply, force);
            lastResult.set(result);
            metrics.recordRun(METRIC_SOURCE, result.outcome().tag(), !apply, result.outcome().failure(),
                    result.finishedAt(), result.entriesCaptured(), Map.of(
                            PICTURE_PROBLEMS, (long) result.pictureProblems().size(),
                            SUSPECT_SHARED_PICTURES, (long) result.suspectSharedPictures().size()));
            return result;
        } finally {
            running.set(false);
        }
    }

    /**
     * @param running    whether a run is under way in this instance
     * @param lastResult the last finished run of this instance since it started, or null
     */
    public record Status(boolean running, LegoWoodenToysCaptureResult lastResult) {
    }
}
