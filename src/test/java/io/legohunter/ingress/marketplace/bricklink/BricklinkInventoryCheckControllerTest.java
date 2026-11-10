package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.ingress.marketplace.bricklink.BricklinkInventoryReconciliationMetrics;
import io.legohunter.ingress.marketplace.bricklink.BricklinkInventoryReconciliationReport;
import io.legohunter.ingress.marketplace.bricklink.BricklinkInventoryReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkInventoryCheckControllerTest {
    private static final ZonedDateTime CHECKED_AT = ZonedDateTime.parse("2026-11-04T02:00:00Z");

    @Test
    void runsBothChecksAndRecordsTheirMetricsWhenEnabled() {
        BricklinkInventoryReconciliationService reconciliation = mock(BricklinkInventoryReconciliationService.class);
        BricklinkInventoryReconciliationMetrics reconciliationMetrics = mock(BricklinkInventoryReconciliationMetrics.class);
        BricklinkLotAuditService audit = mock(BricklinkLotAuditService.class);
        BricklinkLotAuditMetrics auditMetrics = mock(BricklinkLotAuditMetrics.class);
        BricklinkInventoryReconciliationReport reconciliationReport = new BricklinkInventoryReconciliationReport(
                "IN_SYNC", "sandbox", CHECKED_AT, 5, 5, 5, 5, 0, Map.of(), List.of(), null, 10);
        BricklinkLotAuditReport auditReport = new BricklinkLotAuditReport("CLEAN", CHECKED_AT, 5, 5, Map.of(), List.of(), null, 10);
        when(reconciliation.reconcile()).thenReturn(reconciliationReport);
        when(audit.audit()).thenReturn(auditReport);
        BricklinkInventoryCheckController controller = new BricklinkInventoryCheckController(
                provider(BricklinkInventoryReconciliationService.class, reconciliation),
                provider(BricklinkInventoryReconciliationMetrics.class, reconciliationMetrics),
                provider(BricklinkLotAuditService.class, audit),
                provider(BricklinkLotAuditMetrics.class, auditMetrics));

        assertThat(controller.reconcile().getBody()).isSameAs(reconciliationReport);
        assertThat(controller.audit().getBody()).isSameAs(auditReport);
        verify(reconciliationMetrics).record(reconciliationReport);
        verify(auditMetrics).record(auditReport);
    }

    @Test
    void answersNotFoundWhenTheChecksAreNotEnabled() {
        BricklinkInventoryCheckController controller = new BricklinkInventoryCheckController(
                provider(BricklinkInventoryReconciliationService.class, null),
                provider(BricklinkInventoryReconciliationMetrics.class, null),
                provider(BricklinkLotAuditService.class, null),
                provider(BricklinkLotAuditMetrics.class, null));

        assertThat(controller.reconcile().getStatusCode().value()).isEqualTo(404);
        assertThat(controller.audit().getStatusCode().value()).isEqualTo(404);
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (bean != null) {
            factory.addBean(type.getSimpleName(), bean);
        }
        return factory.getBeanProvider(type);
    }
}
