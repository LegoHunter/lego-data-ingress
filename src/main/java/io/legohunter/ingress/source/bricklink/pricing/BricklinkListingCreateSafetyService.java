package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.data.bricklink.BricklinkInventoryColorPolicy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
class BricklinkListingCreateSafetyService {
    private static final String LISTING_CREATE = "LISTING_CREATE";
    private static final String LISTING_STATUS_DRAFT = "DRAFT";
    private static final String INVENTORY_STATE_AVAILABLE = "AVAILABLE";
    private static final String SALE_INTENT_SELLABLE = "SELLABLE";
    private static final String REMOTE_SCOPE_STOCKROOM = "STOCKROOM";
    private static final String REMOTE_SCOPE_PUBLIC = "PUBLIC";

    BricklinkListingCreateSafetyResult verify(
            BricklinkMarketplaceSyncProperties properties,
            MarketplaceListing listing,
            BricklinkMarketplaceListing bricklinkListing,
            ItemInventory itemInventory,
            MarketplaceListingSyncRequest request
    ) {
        return verify(properties, listing, bricklinkListing, itemInventory, request, false);
    }

    /**
     * @param resuming true when an earlier attempt already created (or found) the BrickLink lot and
     *                 saved its id; the "no remote lot yet" rule is then skipped, because the sync
     *                 resumes at the read-back instead of creating another lot
     */
    BricklinkListingCreateSafetyResult verify(
            BricklinkMarketplaceSyncProperties properties,
            MarketplaceListing listing,
            BricklinkMarketplaceListing bricklinkListing,
            ItemInventory itemInventory,
            MarketplaceListingSyncRequest request,
            boolean resuming
    ) {
        if (listing == null || bricklinkListing == null || itemInventory == null || request == null) {
            return blocked("LOCAL_CONTEXT_MISSING", "Required local listing create context is missing");
        }
        if (!LISTING_CREATE.equals(normalize(request.getSyncRequestTypeCode()))) {
            return blocked("UNSUPPORTED_SYNC_TYPE", "Sync request type is not LISTING_CREATE");
        }
        if (!properties.getBricklinkExternalServiceId().equals(listing.getListingExternalServiceId())
                || !properties.getBricklinkExternalServiceId().equals(request.getListingExternalServiceId())) {
            return blocked("NON_BRICKLINK_LISTING", "Marketplace listing does not belong to configured BrickLink external service");
        }
        if (!LISTING_STATUS_DRAFT.equals(normalize(listing.getListingStatusCode()))) {
            return blocked("LISTING_NOT_DRAFT", "LISTING_CREATE requires a local DRAFT marketplace listing");
        }
        if (!resuming && (bricklinkListing.getBricklinkInventoryId() != null || notBlank(request.getRemoteInventoryId()))) {
            return blocked("REMOTE_INVENTORY_ALREADY_EXISTS", "LISTING_CREATE cannot run for a listing that already has a BrickLink inventory id");
        }
        if (!Boolean.TRUE.equals(itemInventory.getActive())) {
            return blocked("INVENTORY_INACTIVE", "Inventory item must be active before BrickLink listing creation");
        }
        if (!SALE_INTENT_SELLABLE.equals(normalize(itemInventory.getSaleIntentCode()))) {
            return blocked("INVENTORY_NOT_SELLABLE", "Inventory item must have saleIntentCode SELLABLE before BrickLink listing creation");
        }
        if (!INVENTORY_STATE_AVAILABLE.equals(normalize(itemInventory.getInventoryStateCode()))) {
            return blocked("INVENTORY_NOT_AVAILABLE", "Inventory item must have inventoryStateCode AVAILABLE before BrickLink listing creation");
        }
        if (listing.getExternalCatalogItem() == null) {
            return blocked("MISSING_BRICKLINK_CATALOG_ITEM", "Marketplace listing must have a BrickLink catalog item before listing creation");
        }
        ExternalCatalogItem catalogItem = listing.getExternalCatalogItem();
        if (blank(catalogItem.getExternalItemKey())) {
            return blocked("MISSING_BRICKLINK_ITEM_NUMBER", "BrickLink catalog item number is required before listing creation");
        }
        if (blank(catalogItem.getItemTypeCode())) {
            return blocked("MISSING_BRICKLINK_ITEM_TYPE", "BrickLink catalog item type is required before listing creation");
        }
        BricklinkInventoryColorPolicy.Resolution color = BricklinkInventoryColorPolicy.resolve(
                catalogItem.getItemTypeCode(),
                bricklinkListing.getColorId()
        );
        if (!color.valid()) {
            return blocked(color.errorCode(), color.message());
        }
        if (blank(itemInventory.getNewOrUsed())) {
            return blocked("MISSING_NEW_OR_USED", "Inventory item newOrUsed is required before BrickLink listing creation");
        }
        if (blank(itemInventory.getCompleteness())) {
            return blocked("MISSING_COMPLETENESS", "Inventory item completeness is required before BrickLink listing creation");
        }
        if (listing.getUnitPrice() == null || listing.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return blocked("INVALID_UNIT_PRICE", "Marketplace listing must have a positive unitPrice before BrickLink listing creation");
        }
        if (request.getRequestedUnitPrice() == null || request.getRequestedUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return blocked("INVALID_REQUESTED_UNIT_PRICE", "Sync request must have a positive requestedUnitPrice before BrickLink listing creation");
        }
        if (!money(listing.getUnitPrice()).equals(money(request.getRequestedUnitPrice()))) {
            return blocked("REQUEST_PRICE_MISMATCH", "Sync request requestedUnitPrice must match marketplace listing unitPrice before BrickLink listing creation");
        }

        boolean stockRoom = requestedStockRoom(properties, bricklinkListing, request);
        String stockRoomId = requestedStockRoomId(properties, bricklinkListing, request, stockRoom);
        boolean publiclyAvailable = !stockRoom;
        if (properties.nonProdEnvironment()) {
            if (Boolean.TRUE.equals(request.getRemoteIsPubliclyAvailable())
                    || REMOTE_SCOPE_PUBLIC.equals(normalize(request.getRemoteVisibilityScopeCode()))) {
                return blocked("NON_PROD_PUBLIC_VISIBILITY_FORBIDDEN", "Non-production BrickLink listing creation must be stockroom-only");
            }
            stockRoom = true;
            stockRoomId = properties.effectiveNonProdStockRoomId();
            publiclyAvailable = false;
        }

        String desiredBlock = BricklinkInventorySystemRemarks.systemBlock(
                properties.effectiveEnvironmentCode(),
                listing.getMarketplaceListingId(),
                itemInventory.getUuid()
        );
        String desiredRemarks = BricklinkInventorySystemRemarks.merge(bricklinkListing.getRemarks(), desiredBlock);
        if (desiredRemarks.length() > properties.effectiveRemarksMaxLength()) {
            return blocked("REMARKS_TOO_LONG", "Generated BrickLink remarks exceed configured maximum length");
        }
        return BricklinkListingCreateSafetyResult.allowed(
                desiredRemarks,
                color.effectiveColorId(),
                stockRoom,
                stockRoomId,
                publiclyAvailable
        );
    }

    private boolean requestedStockRoom(
            BricklinkMarketplaceSyncProperties properties,
            BricklinkMarketplaceListing bricklinkListing,
            MarketplaceListingSyncRequest request
    ) {
        if (properties.nonProdEnvironment()) {
            return true;
        }
        if (REMOTE_SCOPE_STOCKROOM.equals(normalize(request.getRemoteVisibilityScopeCode()))) {
            return true;
        }
        return Boolean.TRUE.equals(bricklinkListing.getIsStockRoom());
    }

    private String requestedStockRoomId(
            BricklinkMarketplaceSyncProperties properties,
            BricklinkMarketplaceListing bricklinkListing,
            MarketplaceListingSyncRequest request,
            boolean stockRoom
    ) {
        if (!stockRoom) {
            return null;
        }
        if (properties.nonProdEnvironment()) {
            return properties.effectiveNonProdStockRoomId();
        }
        if (notBlank(request.getRemoteVisibilityContainerId())) {
            return request.getRemoteVisibilityContainerId().trim().toUpperCase();
        }
        return blank(bricklinkListing.getStockRoomId()) ? null : bricklinkListing.getStockRoomId().trim().toUpperCase();
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean notBlank(String value) {
        return !blank(value);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim().toUpperCase();
    }

    private BricklinkListingCreateSafetyResult blocked(String statusCode, String message) {
        return BricklinkListingCreateSafetyResult.blocked(statusCode, message);
    }
}
