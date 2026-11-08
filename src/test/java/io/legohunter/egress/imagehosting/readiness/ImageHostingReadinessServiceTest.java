package io.legohunter.egress.imagehosting.readiness;

import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.imaging.bitly.config.BitlyProperties;
import io.legohunter.imaging.flickr.config.FlickrProperties;
import io.legohunter.ingress.config.storage.S3ClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImageHostingReadinessServiceTest {

    @Test
    void evaluateReportsReadyWhenRequiredKubernetesConfigIsComplete() {
        ImageHostingReadinessReport report = service(
                environmentWithDatabaseKey(),
                Optional.of(mock(DataSource.class)),
                configuredS3(),
                configuredImageHostingProperties(true, true, true),
                Optional.of(configuredFlickr()),
                Optional.of(configuredBitly())
        ).evaluate();

        assertThat(report.ready()).isTrue();
        assertThat(report.checks())
                .extracting(ImageHostingReadinessCheck::status)
                .containsOnly("UP");
        assertThat(check(report, "scheduled-sync").message())
                .contains("apply=true", "batchSize=25", "concurrency=2");
        assertThat(check(report, "bitly").required()).isTrue();
    }

    @Test
    void evaluateReportsDownWhenKubernetesRequiresScheduledSyncButItIsDisabled() {
        ImageHostingReadinessReport report = service(
                new MockEnvironment(),
                Optional.empty(),
                new S3ClientProperties(),
                configuredImageHostingProperties(false, false, true),
                Optional.empty(),
                Optional.empty()
        ).evaluate();

        assertThat(report.ready()).isFalse();
        assertThat(check(report, "scheduled-sync"))
                .extracting(
                        ImageHostingReadinessCheck::status,
                        ImageHostingReadinessCheck::required
                )
                .containsExactly("DOWN", true);
        assertThat(check(report, "database").message())
                .contains("spring.datasource.database-key-name", "DataSource bean");
        assertThat(check(report, "flickr").message())
                .contains("flickr configuration");
    }

    @Test
    void evaluateAllowsBitlyWarningWhenScheduledSyncRunsInDryRunMode() {
        BitlyProperties bitlyProperties = new BitlyProperties();
        ImageHostingReadinessReport report = service(
                environmentWithDatabaseKey(),
                Optional.of(mock(DataSource.class)),
                configuredS3(),
                configuredImageHostingProperties(true, false, false),
                Optional.of(configuredFlickr()),
                Optional.of(bitlyProperties)
        ).evaluate();

        assertThat(report.ready()).isTrue();
        assertThat(check(report, "bitly"))
                .extracting(
                        ImageHostingReadinessCheck::status,
                        ImageHostingReadinessCheck::required
                )
                .containsExactly("WARN", false);
        assertThat(check(report, "bitly").message())
                .contains("bitly.access-token", "bitly.group-guid");
    }

    @Test
    void healthIndicatorMapsReadinessReportToActuatorHealth() {
        ImageHostingReadinessService readinessService = mock(ImageHostingReadinessService.class);
        ImageHostingReadinessReport report = new ImageHostingReadinessReport(
                false,
                List.of(new ImageHostingReadinessCheck(
                        "flickr",
                        "DOWN",
                        true,
                        "Missing flickr.secrets.token"
                ))
        );
        when(readinessService.evaluate()).thenReturn(report);

        Health health = new ImageHostingReadinessHealthIndicator(readinessService).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("ready", false)
                .containsEntry("checks", report.checks());
    }

    private static ImageHostingReadinessService service(
            MockEnvironment environment,
            Optional<DataSource> dataSource,
            S3ClientProperties s3ClientProperties,
            ImageHostingSyncProperties imageHostingProperties,
            Optional<FlickrProperties> flickrProperties,
            Optional<BitlyProperties> bitlyProperties
    ) {
        return new ImageHostingReadinessService(
                environment,
                dataSource,
                s3ClientProperties,
                imageHostingProperties,
                flickrProperties,
                bitlyProperties
        );
    }

    private static MockEnvironment environmentWithDatabaseKey() {
        return new MockEnvironment()
                .withProperty("spring.datasource.database-key-name", "lego-prod");
    }

    private static S3ClientProperties configuredS3() {
        S3ClientProperties properties = new S3ClientProperties();
        properties.setUrl("http://s3.local");
        properties.setAccessKey("access-key");
        properties.setSecretKey("secret-key");
        return properties;
    }

    private static ImageHostingSyncProperties configuredImageHostingProperties(
            boolean scheduledEnabled,
            boolean applyEnabled,
            boolean requireScheduledSyncEnabled
    ) {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.getSync().getScheduled().setEnabled(scheduledEnabled);
        properties.getSync().getScheduled().setApply(applyEnabled);
        properties.getReadiness().setRequireScheduledSyncEnabled(requireScheduledSyncEnabled);

        ImageHostingSyncProperties.Provider flickr = new ImageHostingSyncProperties.Provider();
        flickr.setEnabled(true);
        flickr.setExternalServiceId(10);
        flickr.setDisplayName("Flickr");
        flickr.setMetricsTag("flickr");
        properties.getProviders().put("flickr", flickr);
        return properties;
    }

    private static FlickrProperties configuredFlickr() {
        FlickrProperties properties = new FlickrProperties();
        properties.setUserId("flickr-user");
        FlickrProperties.Secrets secrets = new FlickrProperties.Secrets();
        secrets.setKey("key");
        secrets.setSecret("secret");
        secrets.setToken("token");
        secrets.setTokenSecret("token-secret");
        properties.setSecrets(secrets);
        return properties;
    }

    private static BitlyProperties configuredBitly() {
        BitlyProperties properties = new BitlyProperties();
        properties.setAccessToken("bitly-token");
        properties.setGroupGuid("bitly-group");
        return properties;
    }

    private static ImageHostingReadinessCheck check(
            ImageHostingReadinessReport report,
            String component
    ) {
        return report.checks().stream()
                .filter(check -> component.equals(check.component()))
                .findFirst()
                .orElseThrow();
    }
}
