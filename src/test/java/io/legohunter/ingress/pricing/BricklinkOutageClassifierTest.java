package io.legohunter.ingress.pricing;

import com.bricklink.api.ajax.exception.BricklinkAjaxClientException;
import com.bricklink.api.ajax.exception.BricklinkAjaxServerException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkOutageClassifierTest {

    @Test
    void brickLinkWideFailuresAreOutages() {
        assertThat(BricklinkOutageClassifier.isOutage(new BricklinkAjaxClientException(302,
                "GET catalogifs.ajax returned [Unexpected redirect to [https://maintenance.bricklink.com:443/]]"))).isTrue();
        assertThat(BricklinkOutageClassifier.isOutage(new BricklinkAjaxClientException(301, "redirect to login"))).isTrue();
        assertThat(BricklinkOutageClassifier.isOutage(new BricklinkAjaxClientException(403, "Forbidden"))).isTrue();
        assertThat(BricklinkOutageClassifier.isOutage(new BricklinkAjaxClientException(429, "Too Many Requests"))).isTrue();
        assertThat(BricklinkOutageClassifier.isOutage(new BricklinkAjaxServerException(500, "Internal Server Error"))).isTrue();
        assertThat(BricklinkOutageClassifier.isOutage(new ResourceAccessException("I/O error: Connection reset"))).isTrue();
        assertThat(BricklinkOutageClassifier.isOutage(new IllegalStateException("302 to maintenance.bricklink.com"))).isTrue();
    }

    @Test
    void anOutageWrappedInAnotherExceptionIsStillAnOutage() {
        RuntimeException wrapped = new IllegalStateException("fetch failed",
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));
        assertThat(BricklinkOutageClassifier.isOutage(wrapped)).isTrue();
        assertThat(BricklinkOutageClassifier.isOutage(new RuntimeException(new IOException("Connection refused")))).isTrue();
    }

    @Test
    void problemsWithOneItemAreNotOutages() {
        assertThat(BricklinkOutageClassifier.isOutage(new BricklinkAjaxClientException("Ambiguous match for 6390-1"))).isFalse();
        assertThat(BricklinkOutageClassifier.isOutage(new BricklinkAjaxClientException(404, "Not Found"))).isFalse();
        assertThat(BricklinkOutageClassifier.isOutage(new BricklinkAjaxClientException(400, "Bad Request"))).isFalse();
        assertThat(BricklinkOutageClassifier.isOutage(new IllegalStateException("blocked"))).isFalse();
        assertThat(BricklinkOutageClassifier.isOutage(new IllegalArgumentException("unparseable price"))).isFalse();
    }
}
