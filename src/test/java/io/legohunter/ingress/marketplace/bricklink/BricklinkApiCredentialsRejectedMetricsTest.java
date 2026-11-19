package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.exception.BricklinkClientException;
import com.bricklink.api.rest.exception.BricklinkServerException;
import io.legohunter.ingress.marketplace.bricklink.BricklinkApiCredentialsRejectedMetrics.Source;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkApiCredentialsRejectedMetricsTest {
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final BricklinkApiCredentialsRejectedMetrics metrics = new BricklinkApiCredentialsRejectedMetrics(meterRegistry);

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void aRefusedTokenIsCounted(int code) {
        boolean counted = metrics.record(Source.ORDER_POLL, new BricklinkClientException(code, "BAD_OAUTH_REQUEST", "TOKEN_IP_MISMATCHED"));

        assertThat(counted).isTrue();
        assertThat(count(Source.ORDER_POLL)).isEqualTo(1.0);
    }

    @Test
    void aRefusedTokenIsCountedWhenWrapped() {
        metrics.record(Source.LOT_AUDIT, new IllegalStateException("wrapped", new BricklinkClientException(401, "m", "d")));

        assertThat(count(Source.LOT_AUDIT)).isEqualTo(1.0);
    }

    @Test
    void theMaintenanceRedirectIsNotCounted() {
        boolean counted = metrics.record(Source.ORDER_POLL, new BricklinkClientException(302,
                "GET https://api.bricklink.com/api/store/v1/orders?direction=in&status=PENDING",
                "Unexpected redirect to [https://maintenance.bricklink.com:443/recovery.html]."));

        assertThat(counted).isFalse();
        assertThat(count(Source.ORDER_POLL)).isZero();
    }

    @Test
    void otherOutagesAndLocalFailuresAreNotCounted() {
        metrics.record(Source.MARKETPLACE_SYNC, new BricklinkClientException(429, "m", "d"));
        metrics.record(Source.MARKETPLACE_SYNC, new BricklinkClientException(404, "m", "d"));
        metrics.record(Source.MARKETPLACE_SYNC, new BricklinkServerException(503, "x"));
        metrics.record(Source.MARKETPLACE_SYNC, new ResourceAccessException("I/O", new ConnectException("refused")));
        metrics.record(Source.MARKETPLACE_SYNC, new IllegalStateException("BrickLink returned 401"));
        metrics.record(Source.MARKETPLACE_SYNC, null);

        assertThat(count(Source.MARKETPLACE_SYNC)).isZero();
    }

    @Test
    void everySourceIsCountedSeparatelyAndStartsAtZero() {
        for (Source source : Source.values()) {
            assertThat(meterRegistry.find(BricklinkApiCredentialsRejectedMetrics.REJECTED).tag("source", source.tag()).counter())
                    .as(source.name()).isNotNull();
            assertThat(count(source)).isZero();
        }

        metrics.record(Source.LOT_IMAGE_SYNC, new BricklinkClientException(403, "m", "d"));

        assertThat(count(Source.LOT_IMAGE_SYNC)).isEqualTo(1.0);
        assertThat(count(Source.EXTENDED_DESCRIPTION_SYNC)).isZero();
    }

    private double count(Source source) {
        return meterRegistry.counter(BricklinkApiCredentialsRejectedMetrics.REJECTED, "source", source.tag()).count();
    }
}
