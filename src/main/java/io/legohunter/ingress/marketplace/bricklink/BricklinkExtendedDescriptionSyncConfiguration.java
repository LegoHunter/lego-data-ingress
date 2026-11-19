package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.configuration.BricklinkRestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The Extended Description sync reads each lot through the official API before it asks the web gateway,
 * so it needs the REST client even where the marketplace sync itself is switched off (HOMELAB-82).
 */
@Configuration
@ConditionalOnProperty(prefix = "lego.bricklink.extended-description-sync", name = "enabled", havingValue = "true")
@Import(BricklinkRestConfiguration.class)
public class BricklinkExtendedDescriptionSyncConfiguration {
}
