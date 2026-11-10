package io.legohunter.ingress.orders;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.orders.sync")
public class BricklinkOrderSyncProperties {
    private String marketplaceCode = "BRICKLINK";
    private String metricsTag = "bricklink";
    private String direction = "in";
    private List<String> statuses = new ArrayList<>(List.of(
            "PENDING",
            "UPDATED",
            "READY",
            "PROCESSING",
            "PAID",
            "PACKED"
    ));
    private boolean includeUnfiledCancelled = true;
    private Scheduled scheduled = new Scheduled();

    public List<String> effectiveStatuses() {
        return statuses.stream()
                .filter(status -> status != null && !status.isBlank())
                .map(status -> status.trim().toUpperCase())
                .distinct()
                .toList();
    }

    public String effectiveDirection() {
        if (direction == null || direction.isBlank()) {
            return "in";
        }
        return direction.trim().toLowerCase();
    }

    public String effectiveMetricsTag() {
        if (metricsTag == null || metricsTag.isBlank()) {
            return "bricklink";
        }
        return metricsTag.trim();
    }

    public String effectiveMarketplaceCode() {
        if (marketplaceCode == null || marketplaceCode.isBlank()) {
            return "BRICKLINK";
        }
        return marketplaceCode.trim().toUpperCase();
    }

    @Getter
    @Setter
    public static class Scheduled {
        private boolean apply = false;
        private boolean enabled = false;
        private long fixedDelayMs = 3_600_000L;
        private long initialDelayMs = 30_000L;
        private String lockAtMostFor = "10m";
        private String lockAtLeastFor = "0s";
    }
}
