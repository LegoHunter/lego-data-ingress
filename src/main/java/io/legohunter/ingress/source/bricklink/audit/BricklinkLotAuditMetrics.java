package io.legohunter.ingress.source.bricklink.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus metrics for the lot audit. Any non-zero violation gauge needs action.
 *
 * <ul>
 *   <li>{@code bricklink_lot_audit_job_total{outcome}}: runs by outcome</li>
 *   <li>{@code bricklink_lot_audit_violations_current{type}}: violations at the last run, by type</li>
 *   <li>{@code bricklink_lot_audit_lots_current{scope}}: lots_checked, lower_env_lots</li>
 *   <li>{@code bricklink_lot_audit_last_success_timestamp_seconds}: when the last run that read the
 *       store finished (epoch seconds)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "lego.bricklink.lot-audit", name = "enabled", havingValue = "true")
public class BricklinkLotAuditMetrics {
    static final String JOB = "bricklink_lot_audit_job";
    static final String VIOLATIONS = "bricklink_lot_audit_violations_current";
    static final String LOTS = "bricklink_lot_audit_lots_current";
    static final String LAST_SUCCESS = "bricklink_lot_audit_last_success_timestamp_seconds";

    private final MeterRegistry meterRegistry;
    private final Map<BricklinkLotAuditViolationType, AtomicLong> violations = new EnumMap<>(BricklinkLotAuditViolationType.class);
    private final AtomicLong lotsChecked = new AtomicLong();
    private final AtomicLong lowerEnvLots = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();

    public BricklinkLotAuditMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (BricklinkLotAuditViolationType type : BricklinkLotAuditViolationType.values()) {
            AtomicLong value = new AtomicLong();
            violations.put(type, value);
            Gauge.builder(VIOLATIONS, value, AtomicLong::get)
                    .description("Lower-environment BrickLink lots that are public or in the wrong stockroom, at the last audit.")
                    .tag("type", type.tag())
                    .register(meterRegistry);
        }
        Gauge.builder(LOTS, lotsChecked, AtomicLong::get).tag("scope", "lots_checked")
                .description("Lots read by the last BrickLink lot audit.").register(meterRegistry);
        Gauge.builder(LOTS, lowerEnvLots, AtomicLong::get).tag("scope", "lower_env_lots")
                .description("Lots read by the last BrickLink lot audit.").register(meterRegistry);
        Gauge.builder(LAST_SUCCESS, lastSuccessEpochSeconds, AtomicLong::get)
                .description("Epoch seconds when the last BrickLink lot audit that read the store finished.")
                .register(meterRegistry);
    }

    public void record(BricklinkLotAuditReport report) {
        Counter.builder(JOB)
                .description("BrickLink lot audit runs by outcome.")
                .tag("outcome", report.outcome().toLowerCase())
                .register(meterRegistry)
                .increment();
        if (BricklinkLotAuditReport.FAILED.equals(report.outcome())) {
            return;
        }
        report.violationCounts().forEach((type, count) -> violations.get(type).set(count));
        lotsChecked.set(report.lotsChecked());
        lowerEnvLots.set(report.lowerEnvLots());
        lastSuccessEpochSeconds.set(report.checkedAt().toEpochSecond());
    }
}
