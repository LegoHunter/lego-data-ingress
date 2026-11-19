package io.legohunter.ingress.marketplace.bricklink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkInventoryReconciliationJobTest {
    private final BricklinkInventoryReconciliationService reconciliation = mock(BricklinkInventoryReconciliationService.class);
    private final BricklinkInventoryReconciliationMetrics metrics = mock(BricklinkInventoryReconciliationMetrics.class);
    private final BricklinkInventoryReconciliationProperties properties = new BricklinkInventoryReconciliationProperties();
    private final BricklinkDescriptionBackfillService backfill = mock(BricklinkDescriptionBackfillService.class);
    private final BricklinkInventoryReconciliationReport report = new BricklinkInventoryReconciliationReport(
            BricklinkInventoryReconciliationReport.DRIFT_FOUND, "sandbox", null, 5, 5, 5, 5, 0, Map.of(), List.of(), null, 10L);
    private BricklinkInventoryReconciliationJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<BricklinkDescriptionBackfillService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(backfill);
        job = new BricklinkInventoryReconciliationJob(reconciliation, metrics, properties, provider);
        when(reconciliation.reconcile()).thenReturn(report);
    }

    @Test
    void withTheSwitchOnDescriptionDriftIsQueued() {
        properties.setQueueDescriptionUpdates(true);

        job.runOnce();

        verify(metrics).record(report);
        verify(backfill).queueDrift(report);
    }

    @Test
    void withTheSwitchOffTheReportIsOnlyRecorded() {
        job.runOnce();

        verify(metrics).record(report);
        verify(backfill, never()).queueDrift(report);
    }
}
