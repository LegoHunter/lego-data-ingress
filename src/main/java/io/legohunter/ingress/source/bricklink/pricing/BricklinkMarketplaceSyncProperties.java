package io.legohunter.ingress.source.bricklink.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.marketplace-sync")
public class BricklinkMarketplaceSyncProperties {
    private boolean enabled = false;
    private String mode = BricklinkMarketplaceSyncMode.DRY_RUN.name();
    private Integer bricklinkExternalServiceId = 2;
    private int batchSize = 5;
    private Duration retryBackoff = Duration.ofHours(6);
    private String environmentCode = "local";
    private boolean production = false;
    private boolean requireSystemRemarksBlock = true;
    private boolean nonProdRequireStockRoom = true;
    /**
     * The stockroom every lot this lower environment creates or updates must sit in: C for SANDBOX
     * (and local), B for DEV. No default: A is reserved for PROD, and an unset value would
     * otherwise silently share a stockroom. Checked at startup by
     * {@link BricklinkMarketplaceSyncEnvironmentValidator} (HOMELAB-20).
     */
    private String nonProdStockRoomId;
    private int remarksMaxLength = 1024;
    private Scheduled scheduled = new Scheduled();

    public BricklinkMarketplaceSyncMode effectiveMode() {
        if ("local".equals(effectiveEnvironmentCode())) {
            return BricklinkMarketplaceSyncMode.DRY_RUN;
        }
        if (mode == null || mode.isBlank()) {
            return BricklinkMarketplaceSyncMode.DRY_RUN;
        }
        return BricklinkMarketplaceSyncMode.valueOf(mode.trim().toUpperCase().replace('-', '_'));
    }

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public Duration effectiveRetryBackoff() {
        return retryBackoff == null || retryBackoff.isNegative() ? Duration.ofHours(6) : retryBackoff;
    }

    public String effectiveEnvironmentCode() {
        if (environmentCode == null || environmentCode.isBlank()) {
            return "local";
        }
        return environmentCode.trim().toLowerCase();
    }

    public boolean nonProdEnvironment() {
        return !production;
    }

    /**
     * The configured non-prod stockroom, normalized. Fails rather than falling back to a default,
     * so a lower environment can never quietly write into another environment's stockroom.
     */
    public String effectiveNonProdStockRoomId() {
        if (nonProdStockRoomId == null || nonProdStockRoomId.isBlank()) {
            throw new IllegalStateException("lego.bricklink.marketplace-sync.non-prod-stock-room-id is not set (SANDBOX=C, DEV=B; HOMELAB-20)");
        }
        return nonProdStockRoomId.trim().toUpperCase();
    }

    public int effectiveRemarksMaxLength() {
        return Math.max(1, remarksMaxLength);
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
