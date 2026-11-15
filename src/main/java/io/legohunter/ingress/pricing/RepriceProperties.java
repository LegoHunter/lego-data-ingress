package io.legohunter.ingress.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code lego.pricing.reprice}: the one pricing job (HOMELAB-23, built in HOMELAB-65). It decides, guards and hands
 * each change to marketplace sync in one pass. This class says what to price and when; the formula is in
 * {@code lego.pricing.reprice.algorithm} ({@link RepriceAlgorithmProperties}) and the send rules in
 * {@code lego.pricing.reprice.guard} ({@link RepriceGuardProperties}). Since HOMELAB-66 it replaces the old
 * {@code lego.bricklink.pricing.decision}, {@code apply-readiness} and {@code apply} blocks.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.pricing.reprice")
public class RepriceProperties {
    private boolean enabled = false;
    private String mode = RepriceMode.DRY_RUN.name();
    /** The BrickLink row of {@code external_service}: Reprice prices that marketplace's listings. */
    private Integer bricklinkExternalServiceId = 2;
    /** Listing statuses Reprice considers: ACTIVE listings and DRAFTs awaiting their first price. */
    private Set<String> priceableListingStatusCodes = Set.of("ACTIVE", "DRAFT");
    /**
     * Consider only listings that already have a current market snapshot. Off, a listing without one is still picked
     * and gets a FAILED (NO_CURRENT_SNAPSHOT) decision; on, it simply waits for the crawler.
     */
    private boolean requireCurrentSnapshot = false;
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

    public Set<String> effectivePriceableListingStatusCodes() {
        Set<String> codes = priceableListingStatusCodes == null ? Set.of() : priceableListingStatusCodes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase())
                .collect(Collectors.toUnmodifiableSet());
        return codes.isEmpty() ? Set.of(RepriceAlgorithmProperties.ACTIVE_LISTING_STATUS) : codes;
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
