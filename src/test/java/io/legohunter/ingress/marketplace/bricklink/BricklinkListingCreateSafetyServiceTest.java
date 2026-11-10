package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkListingCreateSafetyServiceTest {
    private final BricklinkListingCreateSafetyService service = new BricklinkListingCreateSafetyService();

    @Test
    void allowsNonProdCreateOnlyAsStockroomWithSystemRemarks() {
        BricklinkMarketplaceSyncProperties properties = properties(false);

        BricklinkListingCreateSafetyResult result = service.verify(
                properties,
                listing(),
                bricklinkListing(),
                itemInventory(),
                request(false)
        );

        assertThat(result.allowed()).isTrue();
        assertThat(result.stockRoom()).isTrue();
        assertThat(result.stockRoomId()).isEqualTo("C");
        assertThat(result.publiclyAvailable()).isFalse();
        assertThat(result.desiredRemarks()).contains("Human notes");
        assertThat(result.desiredRemarks()).contains("LEGOHUNTER_ENV=sandbox");
        assertThat(result.desiredRemarksHash()).hasSize(64);
        assertThat(result.effectiveColorId()).isZero();
    }

    @Test
    void blocksNonProdPublicVisibilityEvenBeforePayloadMapping() {
        BricklinkListingCreateSafetyResult result = service.verify(
                properties(false),
                listing(),
                bricklinkListing(),
                itemInventory(),
                request(true)
        );

        assertThat(result.allowed()).isFalse();
        assertThat(result.statusCode()).isEqualTo("NON_PROD_PUBLIC_VISIBILITY_FORBIDDEN");
    }

    @Test
    void blocksExistingRemoteInventoryAndMismatchedPrice() {
        BricklinkMarketplaceListing existingRemote = bricklinkListing();
        existingRemote.setBricklinkInventoryId(12345);
        assertThat(service.verify(properties(false), listing(), existingRemote, itemInventory(), request(false)).statusCode())
                .isEqualTo("REMOTE_INVENTORY_ALREADY_EXISTS");

        MarketplaceListingSyncRequest mismatchedPrice = request(false);
        mismatchedPrice.setRequestedUnitPrice(new BigDecimal("200.00"));
        assertThat(service.verify(properties(false), listing(), bricklinkListing(), itemInventory(), mismatchedPrice).statusCode())
                .isEqualTo("REQUEST_PRICE_MISMATCH");
    }

    @Test
    void allowsProductionPublicCreateWhenRequested() {
        BricklinkMarketplaceListing publicListing = bricklinkListing();
        publicListing.setIsStockRoom(false);
        publicListing.setStockRoomId(null);

        BricklinkListingCreateSafetyResult result = service.verify(
                properties(true),
                listing(),
                publicListing,
                itemInventory(),
                request(true)
        );

        assertThat(result.allowed()).isTrue();
        assertThat(result.stockRoom()).isFalse();
        assertThat(result.publiclyAvailable()).isTrue();
        assertThat(result.stockRoomId()).isNull();
    }

    @Test
    void blocksMissingColorForColorSpecificItemBeforeHttpMapping() {
        MarketplaceListing partListing = listing();
        partListing.getExternalCatalogItem().setItemTypeCode("PART");

        BricklinkListingCreateSafetyResult result = service.verify(
                properties(true),
                partListing,
                bricklinkListing(),
                itemInventory(),
                request(true)
        );

        assertThat(result.allowed()).isFalse();
        assertThat(result.statusCode()).isEqualTo("MISSING_BRICKLINK_COLOR_ID");
    }

    private BricklinkMarketplaceSyncProperties properties(boolean production) {
        BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();
        properties.setProduction(production);
        properties.setEnvironmentCode(production ? "prod" : "sandbox");
        properties.setNonProdStockRoomId("C");
        return properties;
    }

    private MarketplaceListing listing() {
        return MarketplaceListing.builder()
                .marketplaceListingId(100)
                .itemInventoryId(200)
                .listingExternalServiceId(2)
                .externalCatalogItemId(300)
                .listingStatusCode("DRAFT")
                .unitPrice(new BigDecimal("219.00"))
                .currencyCode("USD")
                .externalCatalogItem(ExternalCatalogItem.builder()
                        .externalCatalogItemId(300)
                        .externalServiceId(2)
                        .externalItemKey("6390-1")
                        .itemTypeCode("S")
                        .build())
                .build();
    }

    private BricklinkMarketplaceListing bricklinkListing() {
        return BricklinkMarketplaceListing.builder()
                .marketplaceListingId(100)
                .isStockRoom(true)
                .stockRoomId("C")
                .remarks("Human notes")
                .build();
    }

    private ItemInventory itemInventory() {
        ItemInventory itemInventory = new ItemInventory();
        itemInventory.setItemInventoryId(200);
        itemInventory.setUuid("inventory-uuid");
        itemInventory.setActive(true);
        itemInventory.setSaleIntentCode("SELLABLE");
        itemInventory.setInventoryStateCode("AVAILABLE");
        itemInventory.setNewOrUsed("U");
        itemInventory.setCompleteness("C");
        return itemInventory;
    }

    private MarketplaceListingSyncRequest request(boolean publicVisibility) {
        return MarketplaceListingSyncRequest.builder()
                .marketplaceListingId(100)
                .listingExternalServiceId(2)
                .syncRequestTypeCode("LISTING_CREATE")
                .requestedUnitPrice(new BigDecimal("219.00"))
                .currencyCode("USD")
                .remoteVisibilityScopeCode(publicVisibility ? "PUBLIC" : "STOCKROOM")
                .remoteVisibilityContainerId(publicVisibility ? null : "C")
                .remoteIsPubliclyAvailable(publicVisibility)
                .build();
    }
}
