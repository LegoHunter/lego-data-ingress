package io.legohunter.egress.imagehosting.headroom;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/image-hosting")
@Tag(name = "Image Hosting Account", description = "The image host account's item count against its limit.")
public class ImageHostingHeadroomController {
    private final ImageHostingHeadroomGuard headroomGuard;

    @GetMapping("/account-usage")
    @Operation(summary = "How many items the image host account holds, its limit, and how many new photos may still be published.")
    public ResponseEntity<AccountHeadroom> accountUsage() {
        return ResponseEntity.ok(headroomGuard.current());
    }
}
