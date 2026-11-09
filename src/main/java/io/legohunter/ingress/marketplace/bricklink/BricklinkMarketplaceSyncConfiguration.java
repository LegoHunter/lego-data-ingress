package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.configuration.BricklinkRestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@ConditionalOnProperty(prefix = "lego.bricklink.marketplace-sync", name = "enabled", havingValue = "true")
@Import(BricklinkRestConfiguration.class)
public class BricklinkMarketplaceSyncConfiguration {
}
