package io.legohunter.ingress.pricing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/bricklink/pricing")
@Tag(name = "BrickLink Pricing Maintenance", description = "Read-only Pricing Plane maintenance and diagnostics endpoints.")
public class BricklinkPricingMaintenanceReportController {
    private final BricklinkPricingMaintenanceReportService maintenanceReportService;

    @GetMapping("/maintenance-report")
    @Operation(summary = "Build a dry-run Pricing Plane maintenance report.")
    public ResponseEntity<BricklinkPricingMaintenanceReport> maintenanceReport(@RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(maintenanceReportService.buildReport(limit));
    }

    @GetMapping("/data-quality-report")
    @Operation(summary = "List priceable listings whose inventory or catalog data is missing fields the Pricing Plane needs.")
    public ResponseEntity<BricklinkPricingDataQualityReport> dataQualityReport(@RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(maintenanceReportService.buildDataQualityReport(limit));
    }

}
