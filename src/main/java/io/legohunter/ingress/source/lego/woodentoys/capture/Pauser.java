package io.legohunter.ingress.source.lego.woodentoys.capture;

import java.time.Duration;

/** Waits between requests, so a capture does not load the site. A test passes one that does not wait. */
@FunctionalInterface
public interface Pauser {

    void pause(Duration duration);

    static Pauser sleeping() {
        return duration -> {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                return;
            }
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting between requests", e);
            }
        };
    }
}
