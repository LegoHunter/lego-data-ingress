package io.legohunter.ingress.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Want list sync (HOMELAB-32): every environment tells the shared BrickLink crawler what it is pricing by keeping
 * its own rows in {@code marketplace_pricing.want_list_item}. Unlike the crawl, this runs in every environment.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.pricing.want-list")
public class BricklinkWantListSyncProperties {
    private boolean enabled = false;
    /** This environment's code (sandbox, dev, prod); its want list rows carry it. Set from lego.environment.code. */
    private String environmentCode;
    private Integer bricklinkExternalServiceId = 2;
    /** Listing statuses that are priced, so their items are wanted. Same as the decision's. */
    private Set<String> priceableListingStatusCodes = Set.of("ACTIVE", "DRAFT");
    /** Rows this environment has not confirmed for this long are removed: it no longer sells the item. */
    private Duration pruneAfter = Duration.ofDays(2);
    private Scheduled scheduled = new Scheduled();

    public String effectiveEnvironmentCode() {
        if (environmentCode == null || environmentCode.isBlank()) {
            throw new IllegalStateException("lego.bricklink.pricing.want-list.environment-code is not set");
        }
        return environmentCode.trim().toLowerCase();
    }

    public Set<String> effectivePriceableListingStatusCodes() {
        if (priceableListingStatusCodes == null) {
            return Set.of();
        }
        return priceableListingStatusCodes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase())
                .collect(Collectors.toUnmodifiableSet());
    }

    public Duration effectivePruneAfter() {
        return pruneAfter == null || pruneAfter.isNegative() || pruneAfter.isZero() ? Duration.ofDays(2) : pruneAfter;
    }

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        private long fixedDelayMs = 3_600_000L;
        private long initialDelayMs = 60_000L;
        private String lockAtMostFor = "10m";
        private String lockAtLeastFor = "0s";
    }
}
