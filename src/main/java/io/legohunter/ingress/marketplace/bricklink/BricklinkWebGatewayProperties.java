package io.legohunter.ingress.marketplace.bricklink;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * How to reach this environment's BrickLink web gateway (HOMELAB-92, HOMELAB-82). Shared by every sync
 * that writes through it: the lot image sync and the Extended Description sync.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.web-gateway")
public class BricklinkWebGatewayProperties {
    /** Address of the web gateway of this environment, for example http://bricklink-web-gateway:8080. */
    private String baseUrl = "";
    /** Bearer token the gateway expects from its callers. A secret: never logged. */
    private String callerToken = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    /** Longer than the gateway's own two-minute wait for its queue. */
    private Duration requestTimeout = Duration.ofMinutes(3);

    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank() && callerToken != null && !callerToken.isBlank();
    }

    /** Never print the token. */
    @Override
    public String toString() {
        return "BricklinkWebGatewayProperties[baseUrl=" + baseUrl
                + ", callerToken=" + (callerToken == null || callerToken.isBlank() ? "<unset>" : "<set>")
                + ", connectTimeout=" + connectTimeout + ", requestTimeout=" + requestTimeout + "]";
    }
}
