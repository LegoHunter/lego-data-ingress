package io.legohunter.ingress.orders;

import com.bricklink.api.rest.configuration.BricklinkRestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@ConditionalOnProperty(prefix = "lego.bricklink.orders.sync.scheduled", name = "enabled", havingValue = "true")
@Import(BricklinkRestConfiguration.class)
public class BricklinkOrderSyncConfiguration {
}
