package io.legohunter.ingress.orders;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class BricklinkOrderSyncMetricsService {
    private static final String PROVIDER_TAG = "provider";
    private static final String OUTCOME_TAG = "outcome";
    private static final String RESULT_TAG = "result";

    private final MeterRegistry meterRegistry;

    public void recordRun(String providerTag, String outcome, long elapsedMillis) {
        Counter.builder("bricklink_order_sync")
                .tag(PROVIDER_TAG, providerTag(providerTag))
                .tag(OUTCOME_TAG, outcome)
                .register(meterRegistry)
                .increment();

        Timer.builder("bricklink_order_sync_duration")
                .tag(PROVIDER_TAG, providerTag(providerTag))
                .tag(OUTCOME_TAG, outcome)
                .register(meterRegistry)
                .record(elapsedMillis, TimeUnit.MILLISECONDS);
    }

    public void recordOrders(String providerTag, String result, double count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("bricklink_order_sync_order")
                .tag(PROVIDER_TAG, providerTag(providerTag))
                .tag(RESULT_TAG, result)
                .register(meterRegistry)
                .increment(count);
    }

    private String providerTag(String providerTag) {
        return providerTag == null || providerTag.isBlank() ? "bricklink" : providerTag;
    }
}
