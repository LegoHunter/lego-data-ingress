package io.legohunter.egress.imagehosting;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.image-hosting")
public class ImageHostingSyncProperties {
    private String defaultProvider = "flickr";
    private Sync sync = new Sync();
    private Publishing publishing = new Publishing();
    private Readiness readiness = new Readiness();
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public ResolvedProvider resolveProvider(String requestedProvider, Integer requestedExternalServiceId) {
        String providerKey = Optional.ofNullable(requestedProvider)
                .filter(value -> !value.isBlank())
                .orElse(defaultProvider);
        Provider provider = providers.get(providerKey);
        Integer externalServiceId = Optional.ofNullable(requestedExternalServiceId)
                .or(() -> Optional.ofNullable(provider).map(Provider::getExternalServiceId))
                .orElse(sync.getExternalServiceId());

        if (externalServiceId == null) {
            throw new IllegalArgumentException("externalServiceId is required when no configured image-hosting provider is selected");
        }

        String metricsTag = Optional.ofNullable(provider)
                .map(Provider::getMetricsTag)
                .filter(value -> !value.isBlank())
                .orElse(providerKey);
        String displayName = Optional.ofNullable(provider)
                .map(Provider::getDisplayName)
                .filter(value -> !value.isBlank())
                .orElse(providerKey);
        return new ResolvedProvider(providerKey, displayName, metricsTag, externalServiceId);
    }

    public Path getTempDirectory() {
        return sync.getTempDirectory();
    }

    @Getter
    @Setter
    public static class Sync {
        private Integer externalServiceId = 10;
        private Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"), "lego-data-ingress-image-hosting");
        private Scheduled scheduled = new Scheduled();
        private Retry retry = new Retry();
        private Headroom headroom = new Headroom();
    }

    /**
     * Keeps a limited image host account below its item limit: publishing new photos stops once the account
     * holds {@code limit - reserveItems}. An account with no limit is never held back.
     */
    @Getter
    @Setter
    public static class Headroom {
        private boolean enabled = true;
        /** Items kept free below the account's limit. */
        private int reserveItems = 100;
        /** How long one reading of the account is used before it is read again. */
        private int cacheSeconds = 60;
    }

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        private int batchSize = 25;
        private int concurrency = 2;
        private boolean retryFailed = true;
        private boolean apply = false;
        private long fixedDelayMs = 300_000L;
        private long initialDelayMs = 30_000L;
        private String lockAtMostFor = "10m";
        private String lockAtLeastFor = "0s";

        public int effectiveBatchSize() {
            return Math.max(1, batchSize);
        }

        public int effectiveConcurrency() {
            return Math.max(1, concurrency);
        }
    }

    @Getter
    @Setter
    public static class Retry {
        private boolean enabled = true;
        private int maxAttempts = 3;
        private long initialBackoffMs = 500L;
        private double backoffMultiplier = 2.0d;
        private long maxBackoffMs = 5_000L;

        public int effectiveMaxAttempts() {
            if (!enabled) {
                return 1;
            }
            return Math.max(1, maxAttempts);
        }

        public long effectiveInitialBackoffMs() {
            return Math.max(0L, initialBackoffMs);
        }

        public double effectiveBackoffMultiplier() {
            return Math.max(1.0d, backoffMultiplier);
        }

        public long effectiveMaxBackoffMs() {
            return Math.max(effectiveInitialBackoffMs(), maxBackoffMs);
        }
    }

    @Getter
    @Setter
    public static class Provider {
        private Boolean enabled = false;
        private Integer externalServiceId;
        private String displayName;
        private String metricsTag;
    }

    @Getter
    @Setter
    public static class Readiness {
        private boolean enabled = true;
        private boolean requireScheduledSyncEnabled = false;
        private boolean requireBitlyWhenApplyEnabled = true;
    }

    @Getter
    @Setter
    public static class Publishing {
        private Photo photo = new Photo();
    }

    @Getter
    @Setter
    public static class Photo {
        private String titleTemplate = "{captionOrFilename}";
        private String descriptionTemplate = "{captionOrTitle}";
        private List<String> tags = new ArrayList<>();
        private Boolean publicFlag = true;
        private Boolean friendFlag = false;
        private Boolean familyFlag = false;
        private Boolean hidden = false;
        private String safetyLevel = "safe";
    }

    public record ResolvedProvider(String provider, String displayName, String metricsTag, Integer externalServiceId) {
    }
}
