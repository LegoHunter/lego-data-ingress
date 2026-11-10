package io.legohunter.ingress.marketplace.bricklink;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus metrics for the reconciliation job. The gauges hold the result of the last completed
 * run (a FAILED run leaves them as they were, and only moves the run counter), and are registered
 * up front so every drift type reports 0 rather than being absent.
 *
 * <ul>
 *   <li>{@code bricklink_inventory_reconciliation_job_total{outcome}}: runs by outcome</li>
 *   <li>{@code bricklink_inventory_reconciliation_job_duration_seconds{outcome}}: run time</li>
 *   <li>{@code bricklink_inventory_reconciliation_drift_current{type}}: drifts found, by type</li>
 *   <li>{@code bricklink_inventory_reconciliation_lots_current{scope}}: remote_lots, owned_remote_lots,
 *       local_listings, matched_listings, in_flight_listings</li>
 *   <li>{@code bricklink_inventory_reconciliation_last_success_timestamp_seconds}: when the last
 *       run that read the store finished (epoch seconds)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "lego.bricklink.inventory-reconciliation", name = "enabled", havingValue = "true")
public class BricklinkInventoryReconciliationMetrics {
    static final String JOB = "bricklink_inventory_reconciliation_job";
    static final String JOB_DURATION = "bricklink_inventory_reconciliation_job_duration";
    static final String DRIFT = "bricklink_inventory_reconciliation_drift_current";
    static final String LOTS = "bricklink_inventory_reconciliation_lots_current";
    static final String LAST_SUCCESS = "bricklink_inventory_reconciliation_last_success_timestamp_seconds";

    private final MeterRegistry meterRegistry;
    private final Map<BricklinkInventoryDriftType, AtomicLong> drift = new EnumMap<>(BricklinkInventoryDriftType.class);
    private final Map<String, AtomicLong> lots = new LinkedHashMap<>();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();

    public BricklinkInventoryReconciliationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (BricklinkInventoryDriftType type : BricklinkInventoryDriftType.values()) {
            AtomicLong value = new AtomicLong();
            drift.put(type, value);
            Gauge.builder(DRIFT, value, AtomicLong::get)
                    .description("BrickLink lots that disagree with their local listing, by drift type, at the last reconciliation.")
                    .tag("type", type.tag())
                    .register(meterRegistry);
        }
        for (String scope : new String[]{"remote_lots", "owned_remote_lots", "local_listings", "matched_listings", "in_flight_listings"}) {
            AtomicLong value = new AtomicLong();
            lots.put(scope, value);
            Gauge.builder(LOTS, value, AtomicLong::get)
                    .description("Lots and listings compared by the last BrickLink inventory reconciliation.")
                    .tag("scope", scope)
                    .register(meterRegistry);
        }
        Gauge.builder(LAST_SUCCESS, lastSuccessEpochSeconds, AtomicLong::get)
                .description("Epoch seconds when the last BrickLink inventory reconciliation that read the store finished.")
                .register(meterRegistry);
    }

    public void record(BricklinkInventoryReconciliationReport report) {
        Counter.builder(JOB)
                .description("BrickLink inventory reconciliation runs by outcome.")
                .tag("outcome", report.outcome().toLowerCase())
                .register(meterRegistry)
                .increment();
        Timer.builder(JOB_DURATION)
                .tag("outcome", report.outcome().toLowerCase())
                .register(meterRegistry)
                .record(report.elapsedMillis(), TimeUnit.MILLISECONDS);
        if (BricklinkInventoryReconciliationReport.FAILED.equals(report.outcome())) {
            return;
        }
        report.driftCounts().forEach((type, count) -> drift.get(type).set(count));
        lots.get("remote_lots").set(report.remoteLots());
        lots.get("owned_remote_lots").set(report.ownedRemoteLots());
        lots.get("local_listings").set(report.localListings());
        lots.get("matched_listings").set(report.matchedListings());
        lots.get("in_flight_listings").set(report.inFlightListings());
        lastSuccessEpochSeconds.set(report.checkedAt().toEpochSecond());
    }
}
