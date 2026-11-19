package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.exception.BricklinkClientException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Counts the BrickLink REST calls that BrickLink answered with 401 or 403: the API token was refused (HOMELAB-6).
 *
 * <p>{@code bricklink_api_credentials_rejected_total{source}}, one series per BrickLink job.
 *
 * <p>Every BrickLink job treats a refused token like an outage (HOMELAB-121): a warning, no stack trace, and another
 * try on the next run. That keeps the logs quiet, so this counter is what tells the owner. An alert fires when it
 * keeps rising. The usual cause is the home IP address changing, which invalidates the IP-bound token
 * (TOKEN_IP_MISMATCHED); a revoked or expired token and a 403 block count too. A maintenance redirect, a rate limit
 * (429), a 5xx and an I/O failure are outages but say nothing about the token, so they are not counted.
 *
 * <p>Every series is registered at zero on startup, so the first refusal shows as an increase.
 */
@Component
public class BricklinkApiCredentialsRejectedMetrics {
    static final String REJECTED = "bricklink_api_credentials_rejected";

    /** The BrickLink job whose call was refused; the {@code source} label. */
    public enum Source {
        ORDER_POLL,
        LOT_AUDIT,
        INVENTORY_RECONCILIATION,
        MARKETPLACE_SYNC,
        LOT_IMAGE_SYNC,
        EXTENDED_DESCRIPTION_SYNC;

        String tag() {
            return name().toLowerCase();
        }
    }

    private final Map<Source, Counter> counters = new EnumMap<>(Source.class);

    public BricklinkApiCredentialsRejectedMetrics(MeterRegistry meterRegistry) {
        for (Source source : Source.values()) {
            counters.put(source, Counter.builder(REJECTED)
                    .description("BrickLink REST calls answered with 401 or 403: the API token was refused.")
                    .tag("source", source.tag())
                    .register(meterRegistry));
        }
    }

    /**
     * Counts the failure when it is, or was caused by, a 401 or 403 from the BrickLink REST API.
     *
     * @return true when it was counted
     */
    public boolean record(Source source, Throwable failure) {
        if (!isCredentialsRejected(failure)) {
            return false;
        }
        counters.get(source).increment();
        return true;
    }

    /** True when the failure is, or was caused by, a 401 or 403 from the BrickLink REST API. */
    public static boolean isCredentialsRejected(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof BricklinkClientException client && (client.getCode() == 401 || client.getCode() == 403)) {
                return true;
            }
        }
        return false;
    }
}
