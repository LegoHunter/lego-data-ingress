package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.ingress.config.jackson.JacksonConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkMarketplaceSyncConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    JacksonConfiguration.class,
                    BricklinkMarketplaceSyncProperties.class,
                    BricklinkMarketplaceSyncConfiguration.class
            )
            .withPropertyValues(
                    "lego.bricklink.marketplace-sync.enabled=true",
                    "bricklink.rest.uri=https://api.bricklink.com/api/store/v1",
                    "bricklink.rest.consumer.key=consumer-key",
                    "bricklink.rest.consumer.secret=consumer-secret",
                    "bricklink.rest.token.value=token-value",
                    "bricklink.rest.token.secret=token-secret"
            );

    @Test
    void enabledMarketplaceSyncImportsBricklinkRestClient() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("objectMapper");
            assertThat(context).hasSingleBean(BricklinkRestClient.class);
            assertThat(context.getBeansOfType(ObjectMapper.class)).containsKey("objectMapper");
        });
    }
}
