package io.legohunter.ingress.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingMaintenanceReportControllerTest {
    @Test
    void maintenanceReportReturnsServiceReport() {
        BricklinkPricingMaintenanceReportService service = mock(BricklinkPricingMaintenanceReportService.class);
        BricklinkPricingApplyPreviewService applyPreviewService = mock(BricklinkPricingApplyPreviewService.class);
        BricklinkPricingMaintenanceReport expected = new BricklinkPricingMaintenanceReport(
                ZonedDateTime.parse("2026-06-27T12:00:00Z"),
                true,
                null,
                java.util.List.of()
        );
        when(service.buildReport(25)).thenReturn(expected);

        BricklinkPricingMaintenanceReportController controller = new BricklinkPricingMaintenanceReportController(service, applyPreviewService);
        ResponseEntity<BricklinkPricingMaintenanceReport> response = controller.maintenanceReport(25);

        assertThat(response.getBody()).isSameAs(expected);
        verify(service).buildReport(25);
    }

    @Test
    void dataQualityReportReturnsServiceReport() {
        BricklinkPricingMaintenanceReportService service = mock(BricklinkPricingMaintenanceReportService.class);
        BricklinkPricingDataQualityReport expected = new BricklinkPricingDataQualityReport(
                ZonedDateTime.parse("2026-11-04T12:00:00Z"),
                Set.of("ACTIVE"),
                25,
                null,
                Set.of()
        );
        when(service.buildDataQualityReport(25)).thenReturn(expected);

        BricklinkPricingMaintenanceReportController controller = new BricklinkPricingMaintenanceReportController(
                service,
                mock(BricklinkPricingApplyPreviewService.class)
        );
        ResponseEntity<BricklinkPricingDataQualityReport> response = controller.dataQualityReport(25);

        assertThat(response.getBody()).isSameAs(expected);
        verify(service).buildDataQualityReport(25);
    }

    @Test
    void applyPreviewReturnsServiceReport() {
        BricklinkPricingMaintenanceReportService maintenanceService = mock(BricklinkPricingMaintenanceReportService.class);
        BricklinkPricingApplyPreviewService applyPreviewService = mock(BricklinkPricingApplyPreviewService.class);
        BricklinkPricingApplyPreviewReport expected = new BricklinkPricingApplyPreviewReport(
                ZonedDateTime.parse("2026-07-06T12:00:00Z"),
                true,
                "READY_TO_APPLY",
                null,
                new BricklinkPricingApplyPreviewSummary(0, 0, 0, java.util.Map.of(), java.util.Map.of()),
                Set.of()
        );
        when(applyPreviewService.buildPreview("ready_to_apply", null, 50)).thenReturn(expected);

        BricklinkPricingMaintenanceReportController controller = new BricklinkPricingMaintenanceReportController(
                maintenanceService,
                applyPreviewService
        );
        ResponseEntity<BricklinkPricingApplyPreviewReport> response = controller.applyPreview("ready_to_apply", null, 50);

        assertThat(response.getBody()).isSameAs(expected);
        verify(applyPreviewService).buildPreview("ready_to_apply", null, 50);
    }

    @Test
    void dryRunApplySelectionReturnsServiceReport() {
        BricklinkPricingMaintenanceReportService maintenanceService = mock(BricklinkPricingMaintenanceReportService.class);
        BricklinkPricingApplyPreviewService applyPreviewService = mock(BricklinkPricingApplyPreviewService.class);
        BricklinkPricingDryRunApplySelectionReport expected = new BricklinkPricingDryRunApplySelectionReport(
                ZonedDateTime.parse("2026-07-06T12:00:00Z"),
                true,
                0,
                Set.of()
        );
        when(applyPreviewService.buildDryRunApplySelection(10)).thenReturn(expected);

        BricklinkPricingMaintenanceReportController controller = new BricklinkPricingMaintenanceReportController(
                maintenanceService,
                applyPreviewService
        );
        ResponseEntity<BricklinkPricingDryRunApplySelectionReport> response = controller.dryRunApplySelection(10);

        assertThat(response.getBody()).isSameAs(expected);
        verify(applyPreviewService).buildDryRunApplySelection(10);
    }
}
