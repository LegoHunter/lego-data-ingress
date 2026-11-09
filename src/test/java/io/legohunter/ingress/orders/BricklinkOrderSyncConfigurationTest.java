package io.legohunter.ingress.orders;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.ingress.config.jackson.JacksonConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkOrderSyncConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    JacksonConfiguration.class,
                    BricklinkOrderSyncProperties.class,
                    BricklinkOrderSyncConfiguration.class
            )
            .withPropertyValues(
                    "lego.bricklink.orders.sync.scheduled.enabled=true",
                    "bricklink.rest.uri=https://api.bricklink.com/api/store/v1",
                    "bricklink.rest.consumer.key=consumer-key",
                    "bricklink.rest.consumer.secret=consumer-secret",
                    "bricklink.rest.token.value=token-value",
                    "bricklink.rest.token.secret=token-secret"
            );

    @Test
    void enabledConfigurationDoesNotOverrideApplicationObjectMapper() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("objectMapper");
            assertThat(context).doesNotHaveBean("bricklinkRestObjectMapper");
            assertThat(context).hasSingleBean(BricklinkRestClient.class);
            assertThat(context.getBeansOfType(ObjectMapper.class)).containsKey("objectMapper");
            assertThat(context.getBeansOfType(ObjectMapper.class)).doesNotContainKey("bricklinkRestObjectMapper");
        });
    }

    @Test
    void enabledConfigurationStartsWithHttpLoggingEnabled() {
        contextRunner
                .withPropertyValues("bricklink.rest.http-logging.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BricklinkRestClient.class);
                });
    }
}
