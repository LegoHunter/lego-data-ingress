package io.legohunter.ingress.source.bricklink.audit;

import com.bricklink.api.rest.configuration.BricklinkRestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Brings in the BrickLink REST client for the audit, whatever the other BrickLink jobs are set to. */
@Configuration
@ConditionalOnProperty(prefix = "lego.bricklink.lot-audit", name = "enabled", havingValue = "true")
@Import(BricklinkRestConfiguration.class)
public class BricklinkLotAuditConfiguration {
}
