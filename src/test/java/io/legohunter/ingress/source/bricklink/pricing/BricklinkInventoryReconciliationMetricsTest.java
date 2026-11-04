package io.legohunter.ingress.source.bricklink.pricing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkInventoryReconciliationMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BricklinkInventoryReconciliationMetrics metrics = new BricklinkInventoryReconciliationMetrics(registry);

    @Test
    void everyDriftTypeAndScopeReportsZeroBeforeTheFirstRun() {
        for (BricklinkInventoryDriftType type : BricklinkInventoryDriftType.values()) {
            assertThat(drift(type)).isZero();
        }
        for (String scope : List.of("remote_lots", "owned_remote_lots", "local_listings", "matched_listings", "in_flight_listings")) {
            assertThat(lots(scope)).isZero();
        }
    }

    @Test
    void aRunWithDriftSetsTheGaugesAndRecordsTheRun() {
        ZonedDateTime checkedAt = ZonedDateTime.parse("2026-11-04T02:00:00Z");
        metrics.record(report("DRIFT_FOUND", checkedAt, 2));

        assertThat(drift(BricklinkInventoryDriftType.PRICE_DRIFT)).isEqualTo(2);
        assertThat(drift(BricklinkInventoryDriftType.MISSING_REMOTE_LOT)).isZero();
        assertThat(lots("remote_lots")).isEqualTo(7);
        assertThat(lots("owned_remote_lots")).isEqualTo(5);
        assertThat(lots("local_listings")).isEqualTo(5);
        assertThat(lots("matched_listings")).isEqualTo(5);
        assertThat(lots("in_flight_listings")).isOne();
        assertThat(registry.get(BricklinkInventoryReconciliationMetrics.LAST_SUCCESS).gauge().value()).isEqualTo(checkedAt.toEpochSecond());
        assertThat(registry.get(BricklinkInventoryReconciliationMetrics.JOB).tag("outcome", "drift_found").counter().count()).isOne();
        assertThat(registry.get(BricklinkInventoryReconciliationMetrics.JOB_DURATION).tag("outcome", "drift_found").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(40);
    }

    @Test
    void aLaterCleanRunClearsTheDrift() {
        ZonedDateTime checkedAt = ZonedDateTime.parse("2026-11-04T02:00:00Z");
        metrics.record(report("DRIFT_FOUND", checkedAt, 2));
        metrics.record(report("IN_SYNC", checkedAt.plusHours(1), 0));

        assertThat(drift(BricklinkInventoryDriftType.PRICE_DRIFT)).isZero();
        assertThat(registry.get(BricklinkInventoryReconciliationMetrics.JOB).tag("outcome", "in_sync").counter().count()).isOne();
    }

    @Test
    void aFailedRunKeepsTheLastKnownDriftAndOnlyCountsTheRun() {
        ZonedDateTime checkedAt = ZonedDateTime.parse("2026-11-04T02:00:00Z");
        metrics.record(report("DRIFT_FOUND", checkedAt, 2));
        metrics.record(new BricklinkInventoryReconciliationReport(
                "FAILED", "sandbox", checkedAt.plusHours(1), 0, 0, 0, 0, 0, counts(0), List.of(), "401", 3));

        assertThat(drift(BricklinkInventoryDriftType.PRICE_DRIFT)).isEqualTo(2);
        assertThat(lots("remote_lots")).isEqualTo(7);
        assertThat(registry.get(BricklinkInventoryReconciliationMetrics.LAST_SUCCESS).gauge().value()).isEqualTo(checkedAt.toEpochSecond());
        assertThat(registry.get(BricklinkInventoryReconciliationMetrics.JOB).tag("outcome", "failed").counter().count()).isOne();
    }

    private double drift(BricklinkInventoryDriftType type) {
        return registry.get(BricklinkInventoryReconciliationMetrics.DRIFT).tag("type", type.tag()).gauge().value();
    }

    private double lots(String scope) {
        return registry.get(BricklinkInventoryReconciliationMetrics.LOTS).tag("scope", scope).gauge().value();
    }

    private BricklinkInventoryReconciliationReport report(String outcome, ZonedDateTime checkedAt, int priceDrifts) {
        return new BricklinkInventoryReconciliationReport(outcome, "sandbox", checkedAt, 7, 5, 5, 5, 1,
                counts(priceDrifts), List.of(), null, 40);
    }

    private Map<BricklinkInventoryDriftType, Integer> counts(int priceDrifts) {
        Map<BricklinkInventoryDriftType, Integer> counts = new EnumMap<>(BricklinkInventoryDriftType.class);
        for (BricklinkInventoryDriftType type : BricklinkInventoryDriftType.values()) {
            counts.put(type, 0);
        }
        counts.put(BricklinkInventoryDriftType.PRICE_DRIFT, priceDrifts);
        return counts;
    }
}
