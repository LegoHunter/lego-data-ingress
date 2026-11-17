package io.legohunter.egress.imagehosting.removal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lower-environment removal of hosted photos: an item's photos and album, and photos no item owns. Every
 * endpoint answers 403 in production. Each removal has a read-only form to look at first.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/image-hosting")
@Tag(name = "Image Hosting Removal", description = "Lower-environment removal of an item's hosted photos, and of hosted photos no item owns.")
public class ImageHostingRemovalController {
    private final HostedContentRemovalService removalService;
    private final HostedOrphanSweepService orphanSweepService;

    @GetMapping("/item-inventories/{itemInventoryId}/hosted-content")
    @Operation(summary = "List what removing an item's photos and album would delete. Changes nothing.")
    public ResponseEntity<ItemPurgeReport> previewItemPurge(@PathVariable Integer itemInventoryId) {
        return ResponseEntity.ok(removalService.purgeItem(itemInventoryId, false));
    }

    @PostMapping("/item-inventories/{itemInventoryId}/hosted-content/purge")
    @Operation(summary = "Remove an item's photos and album from the image host, the photo bucket and the database.")
    public ResponseEntity<ItemPurgeReport> purgeItem(@PathVariable Integer itemInventoryId) {
        return ResponseEntity.accepted().body(removalService.purgeItem(itemInventoryId, true));
    }

    @GetMapping("/orphans")
    @Operation(summary = "List hosted photos and albums that no inventory item owns. Changes nothing.")
    public ResponseEntity<OrphanSweepReport> listOrphans() {
        return ResponseEntity.ok(orphanSweepService.sweep(false, 0));
    }

    @PostMapping("/orphans/delete")
    @Operation(summary = "Delete hosted photos that no inventory item owns, up to maxDeletes in one run.")
    public ResponseEntity<OrphanSweepReport> deleteOrphans(@RequestParam(defaultValue = "200") int maxDeletes) {
        return ResponseEntity.accepted().body(orphanSweepService.sweep(true, maxDeletes));
    }
}
