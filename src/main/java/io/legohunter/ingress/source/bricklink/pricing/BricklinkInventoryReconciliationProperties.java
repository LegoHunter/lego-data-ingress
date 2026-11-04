package io.legohunter.ingress.source.bricklink.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Settings for the read-only job that compares the BrickLink store with the local listings
 * ({@link BricklinkInventoryReconciliationService}).
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.inventory-reconciliation")
public class BricklinkInventoryReconciliationProperties {
    private boolean enabled = false;
    private Integer bricklinkExternalServiceId = 2;
    private Scheduled scheduled = new Scheduled();

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        private long fixedDelayMs = 3_600_000L;
        private long initialDelayMs = 120_000L;
        private String lockAtMostFor = "10m";
        private String lockAtLeastFor = "0s";
    }
}
