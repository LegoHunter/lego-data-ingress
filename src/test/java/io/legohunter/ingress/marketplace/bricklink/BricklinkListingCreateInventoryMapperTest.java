package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkListingCreateInventoryMapperTest {
    private final BricklinkListingCreateInventoryMapper mapper = new BricklinkListingCreateInventoryMapper();

    @Test
    void mapsListingCreatePayloadForBricklinkInventory() {
        Inventory inventory = mapper.map(
                listing("S", "Description"),
                bricklinkListing(),
                itemInventory(false, "I"),
                request(),
                safety()
        );

        assertThat(inventory.getItem().getNo()).isEqualTo("6390-1");
        assertThat(inventory.getItem().getType()).isEqualTo("SET");
        assertThat(inventory.getColor_id()).isZero();
        assertThat(inventory.getQuantity()).isOne();
        assertThat(inventory.getNew_or_used()).isEqualTo("U");
        assertThat(inventory.getCompleteness()).isEqualTo("B");
        assertThat(inventory.getUnit_price()).isEqualTo(219.00d);
        assertThat(inventory.getDescription()).isEqualTo("Description");
        assertThat(inventory.getRemarks()).contains("LEGOHUNTER_MANAGED=true");
        assertThat(inventory.getIs_stock_room()).isTrue();
        assertThat(inventory.getStock_room_id()).isEqualTo("C");
        assertThat(inventory.getBulk()).isEqualTo(1);
    }

    @Test
    void mapsSealedCompletenessAndFallsBackToTitle() {
        Inventory inventory = mapper.map(
                listing("SET", null),
                bricklinkListing(),
                itemInventory(true, "C"),
                request(),
                safety()
        );

        assertThat(inventory.getCompleteness()).isEqualTo("S");
        assertThat(inventory.getDescription()).isEqualTo("Main Street");
    }

    private MarketplaceListing listing(String itemTypeCode, String description) {
        return MarketplaceListing.builder()
                .marketplaceListingId(100)
                .title("Main Street")
                .description(description)
                .externalCatalogItem(ExternalCatalogItem.builder()
                        .externalItemKey("6390-1")
                        .itemName("Main Street")
                        .itemTypeCode(itemTypeCode)
                        .build())
                .build();
    }

    private BricklinkMarketplaceListing bricklinkListing() {
        return BricklinkMarketplaceListing.builder()
                .bulk(null)
                .isRetain(false)
                .build();
    }

    private ItemInventory itemInventory(boolean sealed, String completeness) {
        ItemInventory itemInventory = new ItemInventory();
        itemInventory.setNewOrUsed("U");
        itemInventory.setCompleteness(completeness);
        itemInventory.setSealed(sealed);
        return itemInventory;
    }

    private MarketplaceListingSyncRequest request() {
        return MarketplaceListingSyncRequest.builder()
                .requestedUnitPrice(new BigDecimal("219.00"))
                .build();
    }

    private BricklinkListingCreateSafetyResult safety() {
        return BricklinkListingCreateSafetyResult.allowed(
                "[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true [SYSTEM_END]",
                0,
                true,
                "C",
                false
        );
    }
}
