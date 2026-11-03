package io.legohunter.ingress.source.rebrickable.catalog.kafka;

import io.legohunter.data.dao.ExternalCatalogItemCategoryDao;
import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dao.ExternalCategoryDao;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ExternalCatalogItemCategory;
import io.legohunter.data.dto.ExternalCategory;
import io.legohunter.ingress.source.rebrickable.catalog.model.RebrickableCatalogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RebrickableCatalogEntryConsumerTest {

    private static final int BRICKLINK_SERVICE_ID = 2;
    private static final int REBRICKABLE_SERVICE_ID = 9;

    @Mock
    private ExternalCatalogItemDao externalCatalogItemDao;
    @Mock
    private ExternalCategoryDao externalCategoryDao;
    @Mock
    private ExternalCatalogItemCategoryDao externalCatalogItemCategoryDao;

    private RebrickableCatalogEntryConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new RebrickableCatalogEntryConsumer(externalCatalogItemDao, externalCategoryDao, externalCatalogItemCategoryDao);
        when(externalCatalogItemDao.upsert(any(ExternalCatalogItem.class)))
                .thenAnswer(invocation -> {
                    ExternalCatalogItem item = invocation.getArgument(0);
                    item.setExternalCatalogItemId(1234);
                    return item;
                });
    }

    @Test
    void linksTheSetToItsRebrickableTheme() {
        ExternalCategory rebrickableTheme = ExternalCategory.builder()
                .externalCategoryId(77)
                .externalServiceId(REBRICKABLE_SERVICE_ID)
                .externalCategoryKey("233")
                .build();
        when(externalCategoryDao.findByExternalServiceIdAndExternalCategoryKey(REBRICKABLE_SERVICE_ID, "233"))
                .thenReturn(Optional.of(rebrickableTheme));

        consumer.listen(entry("10194-1", 233));

        ArgumentCaptor<ExternalCatalogItemCategory> link = ArgumentCaptor.forClass(ExternalCatalogItemCategory.class);
        verify(externalCatalogItemCategoryDao).upsert(link.capture());
        assertThat(link.getValue().getExternalCatalogItemId()).isEqualTo(1234);
        assertThat(link.getValue().getExternalCategoryId()).isEqualTo(77);
    }

    @Test
    void neverLooksTheThemeUpAmongBricklinkCategories() {
        // Rebrickable theme ids and BrickLink category ids are unrelated numbering schemes.
        consumer.listen(entry("10194-1", 233));

        verify(externalCategoryDao).findByExternalServiceIdAndExternalCategoryKey(REBRICKABLE_SERVICE_ID, "233");
        verify(externalCategoryDao, never()).findByExternalServiceIdAndExternalCategoryKey(eq(BRICKLINK_SERVICE_ID), anyString());
    }

    @Test
    void upsertsTheSetAsARebrickableItem() {
        consumer.listen(entry("10194-1", 233));

        ArgumentCaptor<ExternalCatalogItem> item = ArgumentCaptor.forClass(ExternalCatalogItem.class);
        verify(externalCatalogItemDao).upsert(item.capture());
        assertThat(item.getValue().getExternalServiceId()).isEqualTo(REBRICKABLE_SERVICE_ID);
        assertThat(item.getValue().getExternalItemKey()).isEqualTo("10194-1");
    }

    @Test
    void createsNoLinkWhenTheThemeIsNotLoaded() {
        when(externalCategoryDao.findByExternalServiceIdAndExternalCategoryKey(REBRICKABLE_SERVICE_ID, "999"))
                .thenReturn(Optional.empty());

        consumer.listen(entry("10194-1", 999));

        verify(externalCatalogItemCategoryDao, never()).upsert(any());
    }

    private static RebrickableCatalogEntry entry(String setNum, int themeId) {
        RebrickableCatalogEntry entry = new RebrickableCatalogEntry();
        entry.setSetNum(setNum);
        entry.setName("Emerald Night");
        entry.setYear(2009);
        entry.setThemeId(themeId);
        return entry;
    }
}
