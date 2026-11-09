package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.ingress.marketplace.bricklink.BricklinkInventoryReconciliationMetrics;
import io.legohunter.ingress.marketplace.bricklink.BricklinkInventoryReconciliationReport;
import io.legohunter.ingress.marketplace.bricklink.BricklinkInventoryReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs the reconciliation or the lot audit now, instead of waiting for the schedule. Both only
 * read BrickLink and the database; the result also updates the metrics, as a scheduled run would.
 * Answers 404 when the check is not enabled in this environment.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/bricklink/inventory")
@Tag(name = "BrickLink Inventory Checks", description = "Read-only checks of the BrickLink store against local listings and the lower-environment stockroom rules.")
public class BricklinkInventoryCheckController {
    private final ObjectProvider<BricklinkInventoryReconciliationService> reconciliationService;
    private final ObjectProvider<BricklinkInventoryReconciliationMetrics> reconciliationMetrics;
    private final ObjectProvider<BricklinkLotAuditService> auditService;
    private final ObjectProvider<BricklinkLotAuditMetrics> auditMetrics;

    @PostMapping("/reconciliation")
    @Operation(summary = "Compare the BrickLink store with the local listings now (read-only).")
    public ResponseEntity<BricklinkInventoryReconciliationReport> reconcile() {
        BricklinkInventoryReconciliationService service = reconciliationService.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        BricklinkInventoryReconciliationReport report = service.reconcile();
        reconciliationMetrics.ifAvailable(metrics -> metrics.record(report));
        return ResponseEntity.ok(report);
    }

    @PostMapping("/lot-audit")
    @Operation(summary = "Audit the BrickLink store for lower-environment lots that are public or in the wrong stockroom now (read-only).")
    public ResponseEntity<BricklinkLotAuditReport> audit() {
        BricklinkLotAuditService service = auditService.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        BricklinkLotAuditReport report = service.audit();
        auditMetrics.ifAvailable(metrics -> metrics.record(report));
        return ResponseEntity.ok(report);
    }
}
