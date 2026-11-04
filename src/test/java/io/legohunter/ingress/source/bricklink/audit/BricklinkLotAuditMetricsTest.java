package io.legohunter.ingress.source.bricklink.audit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkLotAuditMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BricklinkLotAuditMetrics metrics = new BricklinkLotAuditMetrics(registry);

    @Test
    void everyViolationTypeReportsZeroBeforeTheFirstRun() {
        for (BricklinkLotAuditViolationType type : BricklinkLotAuditViolationType.values()) {
            assertThat(violations(type)).isZero();
        }
        assertThat(registry.get(BricklinkLotAuditMetrics.LAST_SUCCESS).gauge().value()).isZero();
    }

    @Test
    void aRunWithViolationsSetsTheGaugesAndCountsTheRun() {
        ZonedDateTime checkedAt = ZonedDateTime.parse("2026-11-04T02:00:00Z");
        metrics.record(report("VIOLATIONS_FOUND", checkedAt, 1));

        assertThat(violations(BricklinkLotAuditViolationType.LOWER_ENV_LOT_PUBLIC)).isOne();
        assertThat(violations(BricklinkLotAuditViolationType.LOWER_ENV_LOT_IN_PROD_STOCKROOM)).isZero();
        assertThat(registry.get(BricklinkLotAuditMetrics.LOTS).tag("scope", "lots_checked").gauge().value()).isEqualTo(40);
        assertThat(registry.get(BricklinkLotAuditMetrics.LOTS).tag("scope", "lower_env_lots").gauge().value()).isEqualTo(5);
        assertThat(registry.get(BricklinkLotAuditMetrics.LAST_SUCCESS).gauge().value()).isEqualTo(checkedAt.toEpochSecond());
        assertThat(registry.get(BricklinkLotAuditMetrics.JOB).tag("outcome", "violations_found").counter().count()).isOne();
    }

    @Test
    void aFailedRunKeepsTheLastKnownViolationsAndOnlyCountsTheRun() {
        ZonedDateTime checkedAt = ZonedDateTime.parse("2026-11-04T02:00:00Z");
        metrics.record(report("VIOLATIONS_FOUND", checkedAt, 1));

        metrics.record(new BricklinkLotAuditReport("FAILED", checkedAt.plusMinutes(30), 0, 0, counts(0), List.of(), "401", 5));

        // A failed read must not look like "all clear".
        assertThat(violations(BricklinkLotAuditViolationType.LOWER_ENV_LOT_PUBLIC)).isOne();
        assertThat(registry.get(BricklinkLotAuditMetrics.LAST_SUCCESS).gauge().value()).isEqualTo(checkedAt.toEpochSecond());
        assertThat(registry.get(BricklinkLotAuditMetrics.JOB).tag("outcome", "failed").counter().count()).isOne();
    }

    private double violations(BricklinkLotAuditViolationType type) {
        return registry.get(BricklinkLotAuditMetrics.VIOLATIONS).tag("type", type.tag()).gauge().value();
    }

    private BricklinkLotAuditReport report(String outcome, ZonedDateTime checkedAt, int publicLots) {
        return new BricklinkLotAuditReport(outcome, checkedAt, 40, 5, counts(publicLots), List.of(), null, 12);
    }

    private Map<BricklinkLotAuditViolationType, Integer> counts(int publicLots) {
        Map<BricklinkLotAuditViolationType, Integer> counts = new EnumMap<>(BricklinkLotAuditViolationType.class);
        for (BricklinkLotAuditViolationType type : BricklinkLotAuditViolationType.values()) {
            counts.put(type, 0);
        }
        counts.put(BricklinkLotAuditViolationType.LOWER_ENV_LOT_PUBLIC, publicLots);
        return counts;
    }
}
