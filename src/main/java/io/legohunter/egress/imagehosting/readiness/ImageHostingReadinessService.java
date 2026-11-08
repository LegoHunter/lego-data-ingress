package io.legohunter.egress.imagehosting.readiness;

import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.imaging.bitly.config.BitlyProperties;
import io.legohunter.imaging.flickr.config.FlickrProperties;
import io.legohunter.ingress.config.storage.S3ClientProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ImageHostingReadinessService {
    private static final String UP = "UP";
    private static final String WARN = "WARN";
    private static final String DOWN = "DOWN";

    private final Environment environment;
    private final Optional<DataSource> dataSource;
    private final S3ClientProperties s3ClientProperties;
    private final ImageHostingSyncProperties imageHostingProperties;
    private final Optional<FlickrProperties> flickrProperties;
    private final Optional<BitlyProperties> bitlyProperties;

    public ImageHostingReadinessReport evaluate() {
        List<ImageHostingReadinessCheck> checks = new ArrayList<>();
        ImageHostingSyncProperties.Scheduled scheduled = imageHostingProperties.getSync().getScheduled();
        ImageHostingSyncProperties.Readiness readiness = imageHostingProperties.getReadiness();
        boolean scheduledRequired = readiness.isRequireScheduledSyncEnabled();
        boolean scheduledEnabled = scheduled.isEnabled();
        boolean applyEnabled = scheduled.isApply();
        boolean syncRuntimeRequired = scheduledEnabled || scheduledRequired;

        validateScheduledSync(checks, scheduled, scheduledRequired);
        validateDatabase(checks, syncRuntimeRequired);
        validateS3(checks, syncRuntimeRequired);
        validateFlickr(checks, syncRuntimeRequired);
        validateBitly(checks, scheduledEnabled && applyEnabled && readiness.isRequireBitlyWhenApplyEnabled());

        boolean ready = checks.stream().noneMatch(ImageHostingReadinessCheck::down);
        return new ImageHostingReadinessReport(ready, checks);
    }

    private void validateScheduledSync(
            List<ImageHostingReadinessCheck> checks,
            ImageHostingSyncProperties.Scheduled scheduled,
            boolean required
    ) {
        if (!scheduled.isEnabled()) {
            checks.add(check(
                    "scheduled-sync",
                    required ? DOWN : WARN,
                    required,
                    required
                            ? "Scheduled image-hosting sync must be enabled for this deployment"
                            : "Scheduled image-hosting sync is disabled"
            ));
            return;
        }

        if (scheduled.getBatchSize() < 1 || scheduled.getConcurrency() < 1) {
            checks.add(check(
                    "scheduled-sync",
                    DOWN,
                    true,
                    "Scheduled image-hosting sync batch-size and concurrency must both be greater than zero"
            ));
            return;
        }

        checks.add(check(
                "scheduled-sync",
                UP,
                true,
                "Scheduled image-hosting sync is enabled with apply=%s, batchSize=%s, concurrency=%s"
                        .formatted(scheduled.isApply(), scheduled.getBatchSize(), scheduled.getConcurrency())
        ));
    }

    private void validateDatabase(List<ImageHostingReadinessCheck> checks, boolean required) {
        List<String> missing = new ArrayList<>();
        if (!hasText(environment.getProperty("spring.datasource.database-key-name"))) {
            missing.add("spring.datasource.database-key-name");
        }
        if (dataSource.isEmpty()) {
            missing.add("DataSource bean");
        }
        addConfigCheck(checks, "database", required, missing);
    }

    private void validateS3(List<ImageHostingReadinessCheck> checks, boolean required) {
        List<String> missing = new ArrayList<>();
        if (!hasText(s3ClientProperties.getUrl())) {
            missing.add("lego.s3.url");
        }
        if (!hasText(s3ClientProperties.getAccessKey())) {
            missing.add("lego.s3.access-key");
        }
        if (!hasText(s3ClientProperties.getSecretKey())) {
            missing.add("lego.s3.secret-key");
        }
        addConfigCheck(checks, "s3", required, missing);
    }

    private void validateFlickr(List<ImageHostingReadinessCheck> checks, boolean required) {
        List<String> missing = new ArrayList<>();
        ImageHostingSyncProperties.Provider flickrProvider = imageHostingProperties.getProviders().get("flickr");
        if (flickrProvider == null) {
            missing.add("lego.image-hosting.providers.flickr");
        } else {
            if (!Boolean.TRUE.equals(flickrProvider.getEnabled())) {
                missing.add("lego.image-hosting.providers.flickr.enabled");
            }
            if (flickrProvider.getExternalServiceId() == null) {
                missing.add("lego.image-hosting.providers.flickr.external-service-id");
            }
        }

        if (flickrProperties.isEmpty()) {
            missing.add("flickr configuration");
        } else {
            FlickrProperties properties = flickrProperties.get();
            if (!hasText(properties.getUserId())) {
                missing.add("flickr.user-id");
            }
            FlickrProperties.Secrets secrets = properties.getSecrets();
            if (secrets == null) {
                missing.add("flickr.secrets");
            } else {
                if (!hasText(secrets.getKey())) {
                    missing.add("flickr.secrets.key");
                }
                if (!hasText(secrets.getSecret())) {
                    missing.add("flickr.secrets.secret");
                }
                if (!hasText(secrets.getToken())) {
                    missing.add("flickr.secrets.token");
                }
                if (!hasText(secrets.getTokenSecret())) {
                    missing.add("flickr.secrets.token-secret");
                }
            }
        }
        addConfigCheck(checks, "flickr", required, missing);
    }

    private void validateBitly(List<ImageHostingReadinessCheck> checks, boolean required) {
        List<String> missing = new ArrayList<>();
        if (bitlyProperties.isEmpty()) {
            missing.add("bitly configuration");
        } else {
            BitlyProperties properties = bitlyProperties.get();
            if (!hasText(properties.getBaseUrl())) {
                missing.add("bitly.base-url");
            }
            if (!hasText(properties.getAccessToken())) {
                missing.add("bitly.access-token");
            }
            if (!hasText(properties.getGroupGuid())) {
                missing.add("bitly.group-guid");
            }
        }
        addConfigCheck(checks, "bitly", required, missing);
    }

    private void addConfigCheck(
            List<ImageHostingReadinessCheck> checks,
            String component,
            boolean required,
            List<String> missing
    ) {
        if (missing.isEmpty()) {
            checks.add(check(component, UP, required, "%s configuration is present".formatted(component)));
            return;
        }
        checks.add(check(
                component,
                required ? DOWN : WARN,
                required,
                "Missing %s".formatted(String.join(", ", missing))
        ));
    }

    private ImageHostingReadinessCheck check(
            String component,
            String status,
            boolean required,
            String message
    ) {
        return new ImageHostingReadinessCheck(component, status, required, message);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
