package io.legohunter.egress.target.flickr;

import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Flickr beans are imported by name here, and the test profile leaves the Flickr provider off, so the
 * application context tests never build them. This starts them the way a deployed pod does. A new collaborator
 * in lego-imaging that is not reachable from these imports fails here instead of in a crash-looping pod.
 */
class FlickrImageHostingConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(FlickrImageHostingConfiguration.class)
            .withPropertyValues(
                    "flickr.userId=1@N04",
                    "flickr.secrets.key=test-key",
                    "flickr.secrets.secret=test-secret",
                    "flickr.secrets.token=test-token",
                    "flickr.secrets.tokenSecret=test-token-secret"
            );

    @Test
    void theFlickrServiceStartsWhenTheProviderIsEnabled() {
        runner.withPropertyValues("lego.image-hosting.providers.flickr.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ImageHostingService.class);
                });
    }

    @Test
    void noFlickrBeansExistWhenTheProviderIsOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ImageHostingService.class);
        });
    }
}
