package io.legohunter.ingress.marketplace.bricklink;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Settings of the lot image sync: setting a BrickLink lot's custom image from the item's primary
 * photo, through the BrickLink web gateway (HOMELAB-92). How to reach the gateway is in
 * {@link BricklinkWebGatewayProperties}.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.lot-image-sync")
public class BricklinkLotImageSyncProperties {
    private boolean enabled = false;
    /** DRY_RUN or APPLY. It writes only when this and the marketplace sync mode are both APPLY. */
    private String mode = BricklinkMarketplaceSyncMode.DRY_RUN.name();
    /** How many listings one run may queue. */
    private int queueLimit = 25;
    /**
     * How many images one run sends. Small on purpose: the gateway works on one request at a time,
     * an image takes it 10 to 20 seconds, and it also has to keep its session alive.
     */
    private int batchSize = 3;
    private int maxAttempts = 3;
    /** A stored photo above this is scaled down before it is sent. BrickLink turns away large files. */
    private long maxImageBytes = 2_000_000L;
    /**
     * How long a request waits when it could not be sent through no fault of its own: the gateway is
     * signed out or unreachable, or the BrickLink API is down. Much shorter than the retry backoff
     * after a failure, so a gateway restart does not hold lot images back for hours. A retry against
     * a gateway that still cannot work is one cheap refused call, and the run stops there.
     */
    private Duration deferralDelay = Duration.ofMinutes(30);
    private Scheduled scheduled = new Scheduled();

    public BricklinkMarketplaceSyncMode effectiveMode(BricklinkMarketplaceSyncMode marketplaceSyncMode) {
        boolean apply = mode != null && BricklinkMarketplaceSyncMode.APPLY.name().equals(mode.trim().toUpperCase().replace('-', '_'));
        return apply && marketplaceSyncMode == BricklinkMarketplaceSyncMode.APPLY
                ? BricklinkMarketplaceSyncMode.APPLY
                : BricklinkMarketplaceSyncMode.DRY_RUN;
    }

    public int effectiveQueueLimit() {
        return Math.max(1, queueLimit);
    }

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public int effectiveMaxAttempts() {
        return Math.max(1, maxAttempts);
    }

    public Duration effectiveDeferralDelay() {
        return deferralDelay == null || deferralDelay.isNegative() || deferralDelay.isZero() ? Duration.ofMinutes(30) : deferralDelay;
    }

    @Override
    public String toString() {
        return "BricklinkLotImageSyncProperties[enabled=" + enabled + ", mode=" + mode + ", batchSize=" + batchSize + "]";
    }

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        private long fixedDelayMs = 600_000L;
        private long initialDelayMs = 90_000L;
        private String lockAtMostFor = "20m";
        private String lockAtLeastFor = "0s";
    }
}
