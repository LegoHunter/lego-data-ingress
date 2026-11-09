package io.legohunter.ingress;

import com.shipstation.api.rest.client.ShipStationRestClient;
import io.legohunter.egress.fulfillment.FulfillmentSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application starts with no ShipStation configuration at all, as SANDBOX and DEV run once
 * Infisical no longer holds shipstation-configuration.yml for them (ShipStation is PROD-only,
 * HOMELAB-57/59). The ShipStation file is pointed at a name that does not exist, and the fulfillment
 * sync (ShipStation's only caller) is off and the shipstation-rest starter's auto-configuration is
 * excluded (it fails fast without credentials), exactly as in application-sandbox.yml /
 * application-dev.yml.
 */
@SpringBootTest(properties = {
        "test.shipstation-configuration-file=absent-shipstation-configuration.yml",
        "lego.fulfillment.sync.scheduled.enabled=false",
        "spring.autoconfigure.exclude=com.shipstation.api.rest.autoconfigure.ShipStationRestAutoConfiguration"
})
class LegoDataIngressWithoutShipStationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    void startsWithoutShipStationAndCreatesNoShipStationClient() {
        assertThat(environment.containsProperty("shipstation.rest.api-key")).isFalse();
        assertThat(environment.containsProperty("shipstation.rest.api-secret")).isFalse();
        assertThat(context.getBeansOfType(ShipStationRestClient.class)).isEmpty();
        assertThat(context.getBeansOfType(FulfillmentSyncService.class)).isEmpty();
    }
}
