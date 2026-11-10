package io.legohunter.ingress.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * {@code lego.pricing.reprice}: the Reprice job, which replaces the decision, apply-readiness and apply
 * jobs (HOMELAB-23, built in HOMELAB-65).
 *
 * <p>Until the cut-over (HOMELAB-66) the formula settings stay under {@code lego.bricklink.pricing.decision}
 * and the guard settings under {@code lego.bricklink.pricing.apply-readiness}; Reprice reads both, whether
 * or not those jobs are enabled. HOMELAB-66 moves them into this block.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.pricing.reprice")
public class RepriceProperties {
    private boolean enabled = false;
    private String mode = RepriceMode.DRY_RUN.name();
    private int batchSize = 25;
    /** Sync requests handed to marketplace sync during one run; the rest wait for the sync job. */
    private int immediateSyncLimit = 5;
    private int syncRequestMaxAttempts = 3;
    private Scheduled scheduled = new Scheduled();

    public RepriceMode effectiveMode() {
        if (mode == null || mode.isBlank()) {
            return RepriceMode.DRY_RUN;
        }
        return RepriceMode.valueOf(mode.trim().toUpperCase().replace('-', '_'));
    }

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public int effectiveImmediateSyncLimit() {
        return Math.max(0, immediateSyncLimit);
    }

    public int effectiveSyncRequestMaxAttempts() {
        return Math.max(1, syncRequestMaxAttempts);
    }

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        private long fixedDelayMs = 300_000L;
        private long initialDelayMs = 60_000L;
        private String lockAtMostFor = "10m";
        private String lockAtLeastFor = "0s";
    }
}
