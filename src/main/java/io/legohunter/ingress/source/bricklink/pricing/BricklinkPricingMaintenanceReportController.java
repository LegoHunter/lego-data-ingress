package io.legohunter.ingress.source.bricklink.pricing;

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
    private final BricklinkPricingApplyPreviewService applyPreviewService;

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

    @GetMapping("/apply-preview")
    @Operation(summary = "Build a dry-run Pricing Plane apply-readiness preview report.")
    public ResponseEntity<BricklinkPricingApplyPreviewReport> applyPreview(
            @RequestParam(required = false) String readinessStatusCode,
            @RequestParam(required = false) String blockReasonCode,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return ResponseEntity.ok(applyPreviewService.buildPreview(readinessStatusCode, blockReasonCode, limit));
    }

    @GetMapping("/apply-selection/dry-run")
    @Operation(summary = "Select ready-to-apply pricing decisions without mutating marketplace listings.")
    public ResponseEntity<BricklinkPricingDryRunApplySelectionReport> dryRunApplySelection(@RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(applyPreviewService.buildDryRunApplySelection(limit));
    }
}
