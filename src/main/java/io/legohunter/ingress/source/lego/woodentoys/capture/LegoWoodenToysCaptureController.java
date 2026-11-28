package io.legohunter.ingress.source.lego.woodentoys.capture;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Starts the capture of LEGO's wooden toys catalog by hand and shows how the last run went. A run is a dry run
 * unless {@code apply=true}. Answers 404 when the capture is not enabled in this environment.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/catalog-capture/lego-wooden-toys")
@Tag(name = "Catalog Capture", description = "Capture a catalog from its website into the uploads bucket.")
public class LegoWoodenToysCaptureController {
    private final ObjectProvider<LegoWoodenToysCaptureRunner> runner;

    @PostMapping
    @Operation(summary = "Start a capture in the background (dry run unless apply=true). 409 when one is running.")
    public ResponseEntity<LegoWoodenToysCaptureRunner.Status> start(
            @RequestParam(name = "apply", defaultValue = "false") boolean apply,
            @RequestParam(name = "force", defaultValue = "false") boolean force
    ) {
        LegoWoodenToysCaptureRunner captureRunner = runner.getIfAvailable();
        if (captureRunner == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!captureRunner.start(apply, force)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(captureRunner.status());
        }
        return ResponseEntity.accepted().body(captureRunner.status());
    }

    @GetMapping
    @Operation(summary = "Whether a capture is running, and the result of the last one since this instance started.")
    public ResponseEntity<LegoWoodenToysCaptureRunner.Status> status() {
        LegoWoodenToysCaptureRunner captureRunner = runner.getIfAvailable();
        if (captureRunner == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(captureRunner.status());
    }
}
