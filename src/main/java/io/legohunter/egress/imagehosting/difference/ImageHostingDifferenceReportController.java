package io.legohunter.egress.imagehosting.difference;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/image-hosting")
@Tag(name = "Image Hosting Differences", description = "Where the image host differs from the database. Report only: nothing is repaired.")
public class ImageHostingDifferenceReportController {
    private final ImageHostingDifferenceReportService reportService;

    @GetMapping("/differences")
    @Operation(summary = "The last difference report. 204 when none has run since the service started.")
    public ResponseEntity<HostedDifferenceReport> lastReport() {
        return reportService.last()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/differences/run")
    @Operation(summary = "Compare the database's albums with the image host now and return the report. Changes nothing on the host.")
    public ResponseEntity<HostedDifferenceReport> run() {
        return ResponseEntity.ok(reportService.run());
    }
}
