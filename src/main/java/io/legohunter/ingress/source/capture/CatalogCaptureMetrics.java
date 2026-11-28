package io.legohunter.ingress.source.capture;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus metrics for hands-off catalog captures, one set per {@code source} label. A capture runs rarely
 * (monthly), so the alert reads the state of the last run and not a rate.
 *
 * <ul>
 *   <li>{@code catalog_capture_job_total{source,outcome}}: runs by outcome, dry runs included</li>
 *   <li>{@code catalog_capture_last_run_failed{source}}: 1 when the last run that was not a dry run stopped or
 *       failed, else 0</li>
 *   <li>{@code catalog_capture_last_success_timestamp_seconds{source}}: when the last such run ended well</li>
 *   <li>{@code catalog_capture_entries_current{source}}: entries the last such run read</li>
 *   <li>{@code catalog_capture_attention_current{source,kind}}: things the last such run reported for a person
 *       to look at, by kind</li>
 * </ul>
 */
@Component
public class CatalogCaptureMetrics {
    static final String JOB = "catalog_capture_job";
    static final String LAST_RUN_FAILED = "catalog_capture_last_run_failed";
    static final String LAST_SUCCESS = "catalog_capture_last_success_timestamp_seconds";
    static final String ENTRIES = "catalog_capture_entries_current";
    static final String ATTENTION = "catalog_capture_attention_current";

    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicLong> values = new ConcurrentHashMap<>();

    public CatalogCaptureMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** Registers a source's gauges at zero, so they exist before its first run. */
    public void register(String source, String... attentionKinds) {
        gauge(LAST_RUN_FAILED, source, null, "1 when the last catalog capture run stopped or failed.");
        gauge(LAST_SUCCESS, source, null, "Epoch seconds when the last catalog capture run ended well.");
        gauge(ENTRIES, source, null, "Entries the last catalog capture run read.");
        for (String kind : attentionKinds) {
            gauge(ATTENTION, source, kind, "Things the last catalog capture run reported for a person to look at.");
        }
    }

    /** Counts a run. A dry run changes no gauge. */
    public void recordRun(String source, String outcome, boolean dryRun, boolean failed, Instant finishedAt,
                          long entries, Map<String, Long> attention) {
        Counter.builder(JOB)
                .description("Catalog capture runs by outcome.")
                .tag("source", source)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
        if (dryRun) {
            return;
        }
        gauge(LAST_RUN_FAILED, source, null, "").set(failed ? 1 : 0);
        if (failed) {
            return;
        }
        gauge(LAST_SUCCESS, source, null, "").set(finishedAt.getEpochSecond());
        gauge(ENTRIES, source, null, "").set(entries);
        attention.forEach((kind, count) -> gauge(ATTENTION, source, kind, "").set(count));
    }

    private AtomicLong gauge(String name, String source, String kind, String description) {
        return values.computeIfAbsent(name + "|" + source + "|" + kind, ignored -> {
            AtomicLong value = new AtomicLong();
            Gauge.Builder<AtomicLong> builder = Gauge.builder(name, value, AtomicLong::get)
                    .description(description)
                    .tag("source", source);
            if (kind != null) {
                builder.tag("kind", kind);
            }
            builder.register(meterRegistry);
            return value;
        });
    }
}
