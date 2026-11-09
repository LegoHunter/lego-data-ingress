package io.legohunter.ingress.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.pricing.apply")
public class BricklinkPricingApplyProperties {
    private boolean enabled = false;
    private Integer bricklinkExternalServiceId = 2;
    private int batchSize = 25;
    private int syncRequestMaxAttempts = 3;
    private String mode = BricklinkPricingApplyMode.DRY_RUN.name();
    private String environmentCode = "local";
    private Scheduled scheduled = new Scheduled();

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public int effectiveSyncRequestMaxAttempts() {
        return Math.max(1, syncRequestMaxAttempts);
    }

    public BricklinkPricingApplyMode effectiveMode() {
        if (mode == null || mode.isBlank()) {
            return BricklinkPricingApplyMode.DRY_RUN;
        }
        return BricklinkPricingApplyMode.valueOf(mode.trim().toUpperCase().replace('-', '_'));
    }

    public String effectiveEnvironmentCode() {
        if (environmentCode == null || environmentCode.isBlank()) {
            return "local";
        }
        return environmentCode.trim().toLowerCase();
    }

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        private long fixedDelayMs = 300_000L;
        private long initialDelayMs = 30_000L;
        private String lockAtMostFor = "10m";
        private String lockAtLeastFor = "0s";
    }
}
