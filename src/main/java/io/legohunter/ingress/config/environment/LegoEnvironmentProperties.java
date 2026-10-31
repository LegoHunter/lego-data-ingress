package io.legohunter.ingress.config.environment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Runtime environment identity shared by jobs that cause external side effects.
 *
 * <p>All LCMS environments share one BrickLink account and one ShipStation account, so a lower
 * environment (local, sandbox, dev) must never act on real buyer orders. {@code production} is
 * {@code false} unless explicitly enabled, which only {@code application-prod.yml} does.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.environment")
public class LegoEnvironmentProperties {
    /**
     * True only for the PROD runtime. Gates order and fulfillment writes (ShipStation orders,
     * BrickLink order updates, Drive Thru emails). Defaults to non-production.
     */
    private boolean production = false;
}
