package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.exception.BricklinkClientException;
import com.bricklink.api.rest.exception.BricklinkServerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkRestOutageClassifierTest {

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 401, 403, 429, 500, 502, 503})
    void clientWideStatusCodesMayBeOutages(int code) {
        assertThat(BricklinkRestOutageClassifier.mayBeOutage(new BricklinkClientException(code, "m", "d"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 422})
    void rejectionsOfOneRequestAreNot(int code) {
        assertThat(BricklinkRestOutageClassifier.mayBeOutage(new BricklinkClientException(code, "m", "d"))).isFalse();
    }

    @Test
    void serverExceptionsAndIoFailuresMayBeOutagesEvenWhenWrapped() {
        assertThat(BricklinkRestOutageClassifier.mayBeOutage(new BricklinkServerException(500, "boom"))).isTrue();
        assertThat(BricklinkRestOutageClassifier.mayBeOutage(new ResourceAccessException("I/O", new ConnectException("refused")))).isTrue();
        assertThat(BricklinkRestOutageClassifier.mayBeOutage(new IllegalStateException("wrapped", new BricklinkServerException(503, "x")))).isTrue();
    }

    @Test
    void aMaintenanceHostInTheMessageIsAnOutage() {
        assertThat(BricklinkRestOutageClassifier.mayBeOutage(
                new IllegalStateException("redirected to https://maintenance.bricklink.com/"))).isTrue();
    }

    @Test
    void localFailuresAreNot() {
        assertThat(BricklinkRestOutageClassifier.mayBeOutage(new IllegalStateException("BrickLink inventory id is missing"))).isFalse();
        assertThat(BricklinkRestOutageClassifier.mayBeOutage(null)).isFalse();
    }

    @Test
    void theMaintenanceRedirectIsAnOutage() {
        assertThat(BricklinkRestOutageClassifier.mayBeOutage(new BricklinkClientException(302,
                "GET https://api.bricklink.com/api/store/v1/orders?direction=in&status=PENDING",
                "Unexpected redirect to [https://maintenance.bricklink.com:443/recovery.html]."))).isTrue();
    }
}
