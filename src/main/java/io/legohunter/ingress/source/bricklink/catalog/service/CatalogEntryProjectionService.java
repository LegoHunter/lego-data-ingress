package io.legohunter.ingress.source.bricklink.catalog.service;

import io.legohunter.ingress.common.ExternalServiceIds;
import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.ingress.source.bricklink.catalog.model.CatalogEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CatalogEntryProjectionService {


    private final ExternalCatalogItemDao externalCatalogItemDao;

    public void upsert(CatalogEntry entry) {
        ExternalCatalogItem externalCatalogItem = ExternalCatalogItem.builder()
                .externalServiceId(ExternalServiceIds.BRICKLINK)
                .externalItemKey(entry.getItemId())
                .externalUniqueKey(null)
                .itemName(entry.getItemName())
                .itemTypeCode(entry.getItemType())
                .itemUrl(null)
                .yearReleased(entry.getItemYear())
                .build();

        externalCatalogItemDao.upsert(externalCatalogItem);
    }
}
