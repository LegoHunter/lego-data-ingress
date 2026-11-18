package io.legohunter.ingress;

import io.legohunter.ingress.marketplace.bricklink.BricklinkLotImageSyncJob;
import io.legohunter.ingress.marketplace.bricklink.BricklinkLotImageSyncService;
import io.legohunter.ingress.marketplace.bricklink.BricklinkWebGatewayClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application starts with the lot image sync switched on, as SANDBOX runs it, and with
 * no caller token: the token file is optional, and a missing token must not stop the pod (the sync
 * reports that the gateway is not configured and sends nothing).
 */
@SpringBootTest(properties = {
        "lego.bricklink.lot-image-sync.enabled=true",
        "lego.bricklink.lot-image-sync.scheduled.enabled=true",
        "lego.bricklink.lot-image-sync.gateway-base-url=http://bricklink-web-gateway:8080",
        // Long enough that the job never fires during the test.
        "lego.bricklink.lot-image-sync.scheduled.initial-delay-ms=3600000",
        // The BrickLink REST client will not start without its keys. Placeholders: nothing is called.
        "bricklink.rest.uri=https://api.bricklink.com/api/store/v1",
        "bricklink.rest.consumer.key=placeholder-for-tests",
        "bricklink.rest.consumer.secret=placeholder-for-tests",
        "bricklink.rest.token.value=placeholder-for-tests",
        "bricklink.rest.token.secret=placeholder-for-tests"
})
class LegoDataIngressWithLotImageSyncTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsWithTheLotImageSyncAndItsGatewayClient() {
        assertThat(context.getBeansOfType(BricklinkLotImageSyncService.class)).hasSize(1);
        assertThat(context.getBeansOfType(BricklinkLotImageSyncJob.class)).hasSize(1);
        assertThat(context.getBeansOfType(BricklinkWebGatewayClient.class)).hasSize(1);
    }
}
