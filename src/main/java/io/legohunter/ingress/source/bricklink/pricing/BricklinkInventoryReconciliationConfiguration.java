package io.legohunter.ingress.source.bricklink.pricing;

import com.bricklink.api.rest.configuration.BricklinkRestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Brings in the BrickLink REST client when reconciliation is enabled, whether or not sync is. */
@Configuration
@ConditionalOnProperty(prefix = "lego.bricklink.inventory-reconciliation", name = "enabled", havingValue = "true")
@Import(BricklinkRestConfiguration.class)
public class BricklinkInventoryReconciliationConfiguration {
}
