package io.legohunter.ingress.source.lego.woodentoys.capture;

import io.legohunter.ingress.source.capture.CatalogCaptureMetrics;
import io.legohunter.ingress.source.lego.woodentoys.capture.LegoWoodenToysCaptureResult.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegoWoodenToysCaptureRunnerTest {
    private static final Instant ENDED = Instant.parse("2026-12-02T04:40:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LegoWoodenToysCaptureService service = mock(LegoWoodenToysCaptureService.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final Executor executor = queued::add;
    private final LegoWoodenToysCaptureRunner runner =
            new LegoWoodenToysCaptureRunner(service, new CatalogCaptureMetrics(registry), executor);

    @Test
    void aRunRecordsItsResultAndMetrics() {
        when(service.capture(true, false)).thenReturn(result(Outcome.COMPLETED, true, 667, List.of("a picture: 404")));

        assertThat(runner.run(true, false)).isPresent();

        assertThat(runner.status().running()).isFalse();
        assertThat(runner.status().lastResult().outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(counter("completed")).isEqualTo(1.0d);
        assertThat(gauge("catalog_capture_last_run_failed")).isZero();
        assertThat(gauge("catalog_capture_last_success_timestamp_seconds")).isEqualTo(ENDED.getEpochSecond());
        assertThat(gauge("catalog_capture_entries_current")).isEqualTo(667.0d);
        assertThat(attention("picture_problems")).isEqualTo(1.0d);
        assertThat(attention("suspect_shared_pictures")).isZero();
    }

    @Test
    void aStoppedRunSetsTheFailedGaugeAndKeepsTheLastGoodValues() {
        when(service.capture(true, false))
                .thenReturn(result(Outcome.COMPLETED, true, 667, List.of()))
                .thenReturn(result(Outcome.STOPPED, true, 12, List.of()));

        runner.run(true, false);
        runner.run(true, false);

        assertThat(gauge("catalog_capture_last_run_failed")).isEqualTo(1.0d);
        assertThat(gauge("catalog_capture_entries_current")).isEqualTo(667.0d);
        assertThat(counter("stopped")).isEqualTo(1.0d);
    }

    @Test
    void aDryRunIsCountedAndChangesNoGauge() {
        when(service.capture(false, false)).thenReturn(result(Outcome.STOPPED, false, 0, List.of()));

        runner.run(false, false);

        assertThat(counter("stopped")).isEqualTo(1.0d);
        assertThat(gauge("catalog_capture_last_run_failed")).isZero();
    }

    @Test
    void aSecondStartIsRefusedWhileARunIsUnderWay() {
        when(service.capture(anyBoolean(), anyBoolean())).thenReturn(result(Outcome.DRY_RUN, false, 667, List.of()));

        assertThat(runner.start(false, false)).isTrue();
        assertThat(runner.status().running()).isTrue();
        assertThat(runner.start(true, false)).isFalse();
        assertThat(runner.run(true, false)).isEmpty();

        queued.remove(0).run();

        assertThat(runner.status().running()).isFalse();
        assertThat(runner.start(false, false)).isTrue();
        verify(service, times(1)).capture(false, false);
    }

    @Test
    void theFlagIsReleasedWhenTheCaptureThrows() {
        when(service.capture(true, false)).thenThrow(new IllegalStateException("boom"));

        try {
            runner.run(true, false);
        } catch (IllegalStateException expected) {
            // the service reports its own failures; this is the unexpected case
        }

        assertThat(runner.status().running()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theEndpointAnswers202Then409AndNotFoundWhenTheCaptureIsOff() {
        when(service.capture(anyBoolean(), anyBoolean())).thenReturn(result(Outcome.DRY_RUN, false, 667, List.of()));
        ObjectProvider<LegoWoodenToysCaptureRunner> on = mock(ObjectProvider.class);
        when(on.getIfAvailable()).thenReturn(runner);
        ObjectProvider<LegoWoodenToysCaptureRunner> off = mock(ObjectProvider.class);

        LegoWoodenToysCaptureController controller = new LegoWoodenToysCaptureController(on);
        assertThat(controller.start(false, false).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.start(false, false).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(controller.status().getBody().running()).isTrue();

        LegoWoodenToysCaptureController disabled = new LegoWoodenToysCaptureController(off);
        assertThat(disabled.start(true, false).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(disabled.status().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static LegoWoodenToysCaptureResult result(Outcome outcome, boolean apply, int entries, List<String> problems) {
        return new LegoWoodenToysCaptureResult(outcome, apply, ENDED.minusSeconds(60), ENDED, "", 23, 667, entries, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0L, problems, List.of(), null);
    }

    private double counter(String outcome) {
        return registry.get("catalog_capture_job").tag("source", "lego_wooden_toys").tag("outcome", outcome)
                .counter().count();
    }

    private double gauge(String name) {
        return registry.get(name).tag("source", "lego_wooden_toys").gauge().value();
    }

    private double attention(String kind) {
        return registry.get("catalog_capture_attention_current").tag("source", "lego_wooden_toys").tag("kind", kind)
                .gauge().value();
    }
}
