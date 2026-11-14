package io.legohunter.ingress.marketplace.bricklink;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Queues DESCRIPTION_UPDATE requests for lots whose Description drifted from their listing. Dry run unless
 * {@code apply=true}. Answers 404 when marketplace sync or reconciliation is not enabled in this environment.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/bricklink/marketplace-sync")
@Tag(name = "BrickLink Marketplace Sync", description = "Queue marketplace sync requests by hand.")
public class BricklinkDescriptionBackfillController {
    private final ObjectProvider<BricklinkDescriptionBackfillService> backfillService;

    @PostMapping("/description-backfill")
    @Operation(summary = "Queue DESCRIPTION_UPDATE for every owned lot with DESCRIPTION_DRIFT (dry run unless apply=true).")
    public ResponseEntity<BricklinkDescriptionBackfillService.Result> backfill(
            @RequestParam(name = "apply", defaultValue = "false") boolean apply
    ) {
        BricklinkDescriptionBackfillService service = backfillService.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return service.backfill(apply)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
