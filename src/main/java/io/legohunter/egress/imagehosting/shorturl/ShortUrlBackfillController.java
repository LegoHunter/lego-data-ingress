package io.legohunter.egress.imagehosting.shorturl;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sets every Flickr album's short link to its calculated flic.kr/s/ link. Dry run unless {@code apply=true}. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/image-hosting")
@Tag(name = "Image Hosting Sync", description = "Legacy direct image-hosting sync endpoints.")
public class ShortUrlBackfillController {
    private final ShortUrlBackfillService backfillService;

    @PostMapping("/short-url-backfill")
    @Operation(summary = "Give every Flickr album its flic.kr/s/ short link (dry run unless apply=true); lists BrickLink "
            + "Descriptions that would pass 255 characters.")
    public ResponseEntity<ShortUrlBackfillService.Result> backfill(
            @RequestParam(name = "apply", defaultValue = "false") boolean apply
    ) {
        return ResponseEntity.ok(backfillService.backfill(apply));
    }
}
