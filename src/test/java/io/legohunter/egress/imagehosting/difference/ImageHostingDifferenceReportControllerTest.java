package io.legohunter.egress.imagehosting.difference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageHostingDifferenceReportControllerTest {
    private final ImageHostingDifferenceReportService reportService = mock(ImageHostingDifferenceReportService.class);
    private final ImageHostingDifferenceReportController controller = new ImageHostingDifferenceReportController(reportService);

    @Test
    void lastReportIsNoContentBeforeAnyRunAndDoesNotStartOne() {
        when(reportService.last()).thenReturn(Optional.empty());

        ResponseEntity<HostedDifferenceReport> response = controller.lastReport();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(reportService, never()).run();
    }

    @Test
    void lastReportReturnsTheReportOfTheLastRun() {
        HostedDifferenceReport report = report();
        when(reportService.last()).thenReturn(Optional.of(report));

        ResponseEntity<HostedDifferenceReport> response = controller.lastReport();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(report);
    }

    @Test
    void runComparesNowAndReturnsTheReport() {
        HostedDifferenceReport report = report();
        when(reportService.run()).thenReturn(report);

        assertThat(controller.run().getBody()).isSameAs(report);
    }

    private static HostedDifferenceReport report() {
        return new HostedDifferenceReport(
                "sandbox", "flickr", Instant.parse("2026-11-18T08:30:00Z"), 12L, true, null, null,
                1, 0, 1, 0, 0, 0, 1, 0, List.of()
        );
    }
}
