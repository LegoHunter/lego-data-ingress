package io.legohunter.ingress.marketplace.bricklink;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Settings of the Extended Description sync: keeping each BrickLink lot's Extended Description equal to
 * the captions of its item's photos, through the BrickLink web gateway (HOMELAB-82). How to reach the
 * gateway is in {@link BricklinkWebGatewayProperties}.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.extended-description-sync")
public class BricklinkExtendedDescriptionSyncProperties {
    private boolean enabled = false;
    /** DRY_RUN or APPLY. It writes only when this and the marketplace sync mode are both APPLY. */
    private String mode = BricklinkMarketplaceSyncMode.DRY_RUN.name();
    /** How many listings one run may queue. */
    private int queueLimit = 25;
    /** How many texts one run sends. Small: the gateway works on one request at a time, images included. */
    private int batchSize = 3;
    private int maxAttempts = 3;
    /** How long a request waits when the gateway or the BrickLink API cannot work. No attempt is spent. */
    private Duration deferralDelay = Duration.ofMinutes(30);
    private Scheduled scheduled = new Scheduled();

    public BricklinkMarketplaceSyncMode effectiveMode(BricklinkMarketplaceSyncMode marketplaceSyncMode) {
        boolean apply = mode != null && BricklinkMarketplaceSyncMode.APPLY.name().equals(mode.trim().toUpperCase().replace('-', '_'));
        return enabled && apply && marketplaceSyncMode == BricklinkMarketplaceSyncMode.APPLY
                ? BricklinkMarketplaceSyncMode.APPLY
                : BricklinkMarketplaceSyncMode.DRY_RUN;
    }

    /**
     * Whether this environment really writes Extended Descriptions. Only then does the lot Description say
     * "See Extended Description …": elsewhere it would point buyers at an empty field (decision D9).
     */
    public boolean writes(BricklinkMarketplaceSyncMode marketplaceSyncMode) {
        return effectiveMode(marketplaceSyncMode) == BricklinkMarketplaceSyncMode.APPLY;
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

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        private long fixedDelayMs = 600_000L;
        private long initialDelayMs = 150_000L;
        private String lockAtMostFor = "20m";
        private String lockAtLeastFor = "0s";
    }
}
