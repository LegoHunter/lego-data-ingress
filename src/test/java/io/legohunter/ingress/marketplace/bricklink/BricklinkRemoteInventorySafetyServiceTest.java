package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkRemoteInventorySafetyServiceTest {
    private final BricklinkRemoteInventorySafetyService service = new BricklinkRemoteInventorySafetyService();
    private final BricklinkMarketplaceSyncProperties properties = properties();

    @Test
    void allowsNonProdStockroomInventoryWithMatchingSystemBlock() {
        Inventory remote = remoteInventory(systemBlock());
        remote.setRemarks("Human notes " + systemBlock());

        BricklinkRemoteInventorySafetyResult result = service.verify(properties, listing(), bricklinkListing(), inventory(), remote);

        assertThat(result.allowed()).isTrue();
        assertThat(result.statusCode()).isEqualTo("VERIFIED");
        assertThat(result.desiredRemarks()).contains("Human notes");
        assertThat(result.desiredRemarks()).contains(BricklinkInventorySystemRemarks.BEGIN);
        assertThat(result.desiredRemarksHash()).hasSize(64);
    }

    @Test
    void blocksNonProdInventoryOutsideStockroom() {
        Inventory remote = remoteInventory(systemBlock());
        remote.setIs_stock_room(false);

        BricklinkRemoteInventorySafetyResult result = service.verify(properties, listing(), bricklinkListing(), inventory(), remote);

        assertThat(result.allowed()).isFalse();
        assertThat(result.statusCode()).isEqualTo("REMOTE_NOT_STOCKROOM");
    }

    @Test
    void blocksMissingSystemBlockWhenRequired() {
        Inventory remote = remoteInventory("Human notes");

        BricklinkRemoteInventorySafetyResult result = service.verify(properties, listing(), bricklinkListing(), inventory(), remote);

        assertThat(result.allowed()).isFalse();
        assertThat(result.statusCode()).isEqualTo("REMOTE_SYSTEM_BLOCK_MISSING");
    }

    @Test
    void blocksMismatchedEnvironmentAndInventoryIdentity() {
        Inventory remote = remoteInventory("""
                [SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=dev; MARKETPLACE_LISTING_ID=100; ITEM_INVENTORY_UUID=inventory-uuid [SYSTEM_END]
                """);

        BricklinkRemoteInventorySafetyResult result = service.verify(properties, listing(), bricklinkListing(), inventory(), remote);

        assertThat(result.allowed()).isFalse();
        assertThat(result.statusCode()).isEqualTo("REMOTE_SYSTEM_BLOCK_ENV_MISMATCH");
    }

    @Test
    void allowsExactlyMaxLengthRemarksAndBlocksLongerRemarks() {
        String block = systemBlock();
        int humanLength = properties.effectiveRemarksMaxLength() - block.length() - 1;
        Inventory exact = remoteInventory("x".repeat(humanLength) + " " + block);

        BricklinkRemoteInventorySafetyResult exactResult = service.verify(properties, listing(), bricklinkListing(), inventory(), exact);

        assertThat(exactResult.allowed()).isTrue();
        assertThat(exactResult.desiredRemarks()).hasSize(properties.effectiveRemarksMaxLength());

        Inventory tooLong = remoteInventory("x".repeat(humanLength + 1) + " " + block);
        BricklinkRemoteInventorySafetyResult tooLongResult = service.verify(properties, listing(), bricklinkListing(), inventory(), tooLong);

        assertThat(tooLongResult.allowed()).isFalse();
        assertThat(tooLongResult.statusCode()).isEqualTo("REMOTE_REMARKS_TOO_LONG");
    }

    private BricklinkMarketplaceSyncProperties properties() {
        BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();
        properties.setEnvironmentCode("sandbox");
        properties.setNonProdStockRoomId("A");
        properties.setRemarksMaxLength(1024);
        return properties;
    }

    private MarketplaceListing listing() {
        return MarketplaceListing.builder()
                .marketplaceListingId(100)
                .itemInventoryId(200)
                .listingExternalServiceId(2)
                .build();
    }

    private BricklinkMarketplaceListing bricklinkListing() {
        return BricklinkMarketplaceListing.builder()
                .marketplaceListingId(100)
                .bricklinkInventoryId(12345)
                .build();
    }

    private ItemInventory inventory() {
        ItemInventory itemInventory = new ItemInventory();
        itemInventory.setItemInventoryId(200);
        itemInventory.setUuid("inventory-uuid");
        return itemInventory;
    }

    private Inventory remoteInventory(String remarks) {
        Inventory inventory = new Inventory();
        inventory.setInventory_id(12345L);
        inventory.setIs_stock_room(true);
        inventory.setStock_room_id("A");
        inventory.setRemarks(remarks);
        return inventory;
    }

    private String systemBlock() {
        return "[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=sandbox; MARKETPLACE_LISTING_ID=100; ITEM_INVENTORY_UUID=inventory-uuid [SYSTEM_END]";
    }
}
