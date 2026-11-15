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
        BricklinkPricingMaintenanceReport expected = new BricklinkPricingMaintenanceReport(
                ZonedDateTime.parse("2026-06-27T12:00:00Z"),
                true,
                null,
                java.util.List.of()
        );
        when(service.buildReport(25)).thenReturn(expected);

        BricklinkPricingMaintenanceReportController controller = new BricklinkPricingMaintenanceReportController(service);
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

        BricklinkPricingMaintenanceReportController controller = new BricklinkPricingMaintenanceReportController(service);
        ResponseEntity<BricklinkPricingDataQualityReport> response = controller.dataQualityReport(25);

        assertThat(response.getBody()).isSameAs(expected);
        verify(service).buildDataQualityReport(25);
    }

}
