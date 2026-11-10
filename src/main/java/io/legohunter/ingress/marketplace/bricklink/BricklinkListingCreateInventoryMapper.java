package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.model.v1.Inventory;
import com.bricklink.api.rest.model.v1.Item;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
class BricklinkListingCreateInventoryMapper {
    Inventory map(
            MarketplaceListing listing,
            BricklinkMarketplaceListing bricklinkListing,
            ItemInventory itemInventory,
            MarketplaceListingSyncRequest request,
            BricklinkListingCreateSafetyResult safety
    ) {
        Inventory inventory = new Inventory();
        inventory.setItem(item(listing.getExternalCatalogItem()));
        inventory.setColor_id(safety.effectiveColorId());
        inventory.setQuantity(1);
        inventory.setNew_or_used(normalizeCondition(itemInventory.getNewOrUsed()));
        inventory.setCompleteness(normalizeCompleteness(itemInventory));
        inventory.setUnit_price(doubleValue(request.getRequestedUnitPrice()));
        inventory.setDescription(description(listing));
        inventory.setRemarks(safety.desiredRemarks());
        inventory.setBulk(bricklinkListing.getBulk() == null ? 1 : bricklinkListing.getBulk());
        inventory.setIs_retain(bricklinkListing.getIsRetain());
        inventory.setIs_stock_room(safety.stockRoom());
        inventory.setStock_room_id(safety.stockRoomId());
        inventory.setSale_rate(bricklinkListing.getSaleRate());
        inventory.setTier_quantity1(bricklinkListing.getTierQuantity1());
        inventory.setTier_price1(doubleValue(bricklinkListing.getTierPrice1()));
        inventory.setTier_quantity2(bricklinkListing.getTierQuantity2());
        inventory.setTier_price2(doubleValue(bricklinkListing.getTierPrice2()));
        inventory.setTier_quantity3(bricklinkListing.getTierQuantity3());
        inventory.setTier_price3(doubleValue(bricklinkListing.getTierPrice3()));
        inventory.setMy_weight(doubleValue(bricklinkListing.getMyWeight()));
        return inventory;
    }

    private Item item(ExternalCatalogItem catalogItem) {
        Item item = new Item();
        item.setNo(catalogItem.getExternalItemKey());
        item.setName(catalogItem.getItemName());
        item.setType(bricklinkItemType(catalogItem.getItemTypeCode()));
        return item;
    }

    private String bricklinkItemType(String itemTypeCode) {
        return switch (normalize(itemTypeCode)) {
            case "S", "SET" -> "SET";
            case "P", "PART" -> "PART";
            case "M", "MINIFIG" -> "MINIFIG";
            case "B", "BOOK" -> "BOOK";
            case "G", "GEAR" -> "GEAR";
            case "C", "CATALOG" -> "CATALOG";
            case "I", "INSTRUCTION" -> "INSTRUCTION";
            case "U", "UNSORTED_LOT" -> "UNSORTED_LOT";
            case "O", "ORIGINAL_BOX" -> "ORIGINAL_BOX";
            default -> normalize(itemTypeCode);
        };
    }

    private String normalizeCondition(String value) {
        return "N".equals(normalize(value)) ? "N" : "U";
    }

    private String normalizeCompleteness(ItemInventory itemInventory) {
        if (Boolean.TRUE.equals(itemInventory.getSealed())) {
            return "S";
        }
        return switch (normalize(itemInventory.getCompleteness())) {
            case "C", "COMPLETE" -> "C";
            case "I", "B", "INCOMPLETE" -> "B";
            case "S", "SEALED" -> "S";
            default -> normalize(itemInventory.getCompleteness());
        };
    }

    private String description(MarketplaceListing listing) {
        if (listing.getDescription() != null && !listing.getDescription().isBlank()) {
            return listing.getDescription().trim();
        }
        return listing.getTitle() == null ? null : listing.getTitle().trim();
    }

    private Double doubleValue(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim().toUpperCase();
    }
}
