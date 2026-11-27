package io.legohunter.ingress.source.lego.woodentoys.capture;

import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dao.LegoWoodenToyCatalogDao;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ExternalService;
import io.legohunter.data.dto.LegoWoodenToyCatalogItem;
import io.legohunter.data.dto.LegoWoodenToyCatalogItemImage;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Reads the stored catalog through the lego-data DAOs. Picture bytes are never read. */
@RequiredArgsConstructor
public class DaoLegoWoodenToysCatalogState implements LegoWoodenToysCatalogState {
    private final ExternalCatalogItemDao externalCatalogItemDao;
    private final LegoWoodenToyCatalogDao woodenToyCatalogDao;

    @Override
    public int capturedEntryCount() {
        return woodenToyCatalogDao.countItemsBySource(LegoWoodenToyCatalogItem.SOURCE_LEGO_COM);
    }

    @Override
    public Optional<HeldEntry> find(String uid, String slug) {
        int serviceId = ExternalService.Service.LEGO.getExternalServiceId();
        Optional<ExternalCatalogItem> catalogItem = externalCatalogItemDao
                .findByExternalServiceIdAndExternalUniqueKey(serviceId, uid)
                .or(() -> externalCatalogItemDao.findByExternalServiceIdAndExternalItemKey(serviceId, slug));
        return catalogItem
                .map(ExternalCatalogItem::getExternalCatalogItemId)
                .flatMap(woodenToyCatalogDao::findItem)
                .filter(item -> LegoWoodenToyCatalogItem.SOURCE_LEGO_COM.equals(item.getEntrySource()))
                .map(this::held);
    }

    private HeldEntry held(LegoWoodenToyCatalogItem item) {
        Set<String> assetIds = woodenToyCatalogDao.findImages(item.getExternalCatalogItemId()).stream()
                .filter(image -> LegoWoodenToyCatalogItemImage.SOURCE_LEGO_COM.equals(image.getImageSource()))
                .map(LegoWoodenToyCatalogItemImage::getSourceAssetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Instant updatedAt = item.getSourceUpdatedAt() == null ? null : item.getSourceUpdatedAt().toInstant();
        return new HeldEntry(item.getSourceVersion(), updatedAt, assetIds);
    }
}
