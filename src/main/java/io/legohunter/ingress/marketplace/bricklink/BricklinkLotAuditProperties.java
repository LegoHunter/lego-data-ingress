package io.legohunter.ingress.marketplace.bricklink;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Settings for the lower-environment lot audit ({@link BricklinkLotAuditService}). It has its own
 * switch and schedule, separate from marketplace sync and the Pricing Plane, so turning those off
 * does not turn the audit off.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.lot-audit")
public class BricklinkLotAuditProperties {
    private boolean enabled = false;
    private Scheduled scheduled = new Scheduled();

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        private long fixedDelayMs = 1_800_000L;
        private long initialDelayMs = 60_000L;
        private String lockAtMostFor = "10m";
        private String lockAtLeastFor = "0s";
    }
}
