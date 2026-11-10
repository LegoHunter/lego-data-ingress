package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

@Component
public class BricklinkRemoteInventorySafetyService {

    BricklinkRemoteInventorySafetyResult verify(
            BricklinkMarketplaceSyncProperties properties,
            MarketplaceListing listing,
            BricklinkMarketplaceListing bricklinkListing,
            ItemInventory itemInventory,
            Inventory remoteInventory
    ) {
        if (listing == null || bricklinkListing == null || itemInventory == null || remoteInventory == null) {
            return BricklinkRemoteInventorySafetyResult.blocked("REMOTE_INVENTORY_MISSING", "Required local or remote inventory context is missing");
        }
        if (remoteInventory.getInventory_id() == null || bricklinkListing.getBricklinkInventoryId() == null
                || !Objects.equals(remoteInventory.getInventory_id(), bricklinkListing.getBricklinkInventoryId().longValue())) {
            return BricklinkRemoteInventorySafetyResult.blocked("REMOTE_INVENTORY_ID_MISMATCH", "Remote inventory id does not match local BrickLink listing mapping");
        }
        if (properties.nonProdEnvironment() && properties.isNonProdRequireStockRoom()) {
            if (!Boolean.TRUE.equals(remoteInventory.getIs_stock_room())) {
                return BricklinkRemoteInventorySafetyResult.blocked("REMOTE_NOT_STOCKROOM", "Non-prod BrickLink inventory must be stockroom-only");
            }
            if (!properties.effectiveNonProdStockRoomId().equalsIgnoreCase(nullToBlank(remoteInventory.getStock_room_id()))) {
                return BricklinkRemoteInventorySafetyResult.blocked("REMOTE_STOCKROOM_MISMATCH", "Remote stockroom does not match configured non-prod stockroom");
            }
        }

        Optional<BricklinkInventorySystemRemarks.SystemBlock> block = BricklinkInventorySystemRemarks.parse(remoteInventory.getRemarks());
        if (properties.isRequireSystemRemarksBlock() && block.isEmpty()) {
            return BricklinkRemoteInventorySafetyResult.blocked("REMOTE_SYSTEM_BLOCK_MISSING", "Remote remarks are missing a valid system block");
        }
        if (block.isPresent()) {
            BricklinkRemoteInventorySafetyResult blockResult = verifyBlock(properties, listing, itemInventory, block.get());
            if (!blockResult.allowed()) {
                return blockResult;
            }
        }

        String desiredBlock = BricklinkInventorySystemRemarks.systemBlock(
                properties.effectiveEnvironmentCode(),
                listing.getMarketplaceListingId(),
                itemInventory.getUuid()
        );
        String desiredRemarks = BricklinkInventorySystemRemarks.merge(remoteInventory.getRemarks(), desiredBlock);
        if (desiredRemarks.length() > properties.effectiveRemarksMaxLength()) {
            return BricklinkRemoteInventorySafetyResult.blocked("REMOTE_REMARKS_TOO_LONG", "Generated BrickLink remarks exceed configured maximum length");
        }
        return BricklinkRemoteInventorySafetyResult.allowed(desiredRemarks);
    }

    private BricklinkRemoteInventorySafetyResult verifyBlock(
            BricklinkMarketplaceSyncProperties properties,
            MarketplaceListing listing,
            ItemInventory itemInventory,
            BricklinkInventorySystemRemarks.SystemBlock block
    ) {
        if (!"true".equalsIgnoreCase(block.value(BricklinkInventorySystemRemarks.MANAGED_KEY))) {
            return BricklinkRemoteInventorySafetyResult.blocked("REMOTE_SYSTEM_BLOCK_UNMANAGED", "System block does not mark inventory as LegoHunter managed");
        }
        if (!properties.effectiveEnvironmentCode().equalsIgnoreCase(nullToBlank(block.value(BricklinkInventorySystemRemarks.ENV_KEY)))) {
            return BricklinkRemoteInventorySafetyResult.blocked("REMOTE_SYSTEM_BLOCK_ENV_MISMATCH", "System block environment does not match runtime environment");
        }
        if (!String.valueOf(listing.getMarketplaceListingId()).equals(block.value(BricklinkInventorySystemRemarks.MARKETPLACE_LISTING_ID_KEY))) {
            return BricklinkRemoteInventorySafetyResult.blocked("REMOTE_SYSTEM_BLOCK_LISTING_MISMATCH", "System block marketplace listing id does not match local listing");
        }
        if (!nullToBlank(itemInventory.getUuid()).equals(block.value(BricklinkInventorySystemRemarks.ITEM_INVENTORY_UUID_KEY))) {
            return BricklinkRemoteInventorySafetyResult.blocked("REMOTE_SYSTEM_BLOCK_INVENTORY_MISMATCH", "System block inventory UUID does not match local inventory");
        }
        return BricklinkRemoteInventorySafetyResult.allowed(null);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value.trim();
    }
}
