package io.legohunter.ingress.pricing;

import io.legohunter.data.dao.CanonicalItemDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.WantListItemDao;
import io.legohunter.data.dto.CanonicalItem;
import io.legohunter.data.dto.WantListItem;
import io.legohunter.data.dto.WantedCatalogItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkWantListSyncServiceTest {
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-11-09T19:00:00Z");

    private MarketplaceListingDao marketplaceListingDao;
    private CanonicalItemDao canonicalItemDao;
    private WantListItemDao wantListItemDao;
    private BricklinkWantListSyncProperties properties;
    private BricklinkWantListSyncService service;

    @BeforeEach
    void setUp() {
        marketplaceListingDao = mock(MarketplaceListingDao.class);
        canonicalItemDao = mock(CanonicalItemDao.class);
        wantListItemDao = mock(WantListItemDao.class);
        properties = new BricklinkWantListSyncProperties();
        properties.setEnabled(true);
        properties.setEnvironmentCode("Sandbox");
        properties.setPruneAfter(Duration.ofDays(2));
        service = new BricklinkWantListSyncService(properties, marketplaceListingDao, canonicalItemDao, wantListItemDao) {
            @Override
            ZonedDateTime now() {
                return NOW;
            }
        };
    }

    @Test
    void confirmsEachWantedItemUnderThisEnvironmentAndPrunesWhatItNoLongerSells() {
        when(marketplaceListingDao.findWantedCatalogItemsByListingExternalServiceIdAndListingStatusCodes(2, Set.of("ACTIVE", "DRAFT")))
                .thenReturn(List.of(wanted("6390-1", "N", "S"), wanted("6541-1", "U", "C")));
        when(canonicalItemDao.findByItemTypeCodeAndItemNumber("S", "6390-1")).thenReturn(Optional.of(canonical(7, "6390-1")));
        when(canonicalItemDao.findByItemTypeCodeAndItemNumber("S", "6541-1")).thenReturn(Optional.empty());
        when(canonicalItemDao.insert(any())).thenAnswer(invocation -> {
            CanonicalItem item = invocation.getArgument(0);
            item.setCanonicalItemId(8);
            return item;
        });
        when(wantListItemDao.deleteByEnvironmentCodeAndLastConfirmedAtBefore("sandbox", NOW.minusDays(2))).thenReturn(4);

        BricklinkWantListSyncResult result = service.runOnce();

        assertThat(result.environmentCode()).isEqualTo("sandbox");
        assertThat(result.itemsConfirmed()).isEqualTo(2);
        assertThat(result.canonicalItemsAdded()).isOne();
        assertThat(result.itemsPruned()).isEqualTo(4);
        ArgumentCaptor<WantListItem> rows = ArgumentCaptor.forClass(WantListItem.class);
        verify(wantListItemDao, times(2)).upsert(rows.capture());
        assertThat(rows.getAllValues()).extracting(WantListItem::getEnvironmentCode).containsOnly("sandbox");
        assertThat(rows.getAllValues()).extracting(WantListItem::getCanonicalItemId).containsExactly(7, 8);
        assertThat(rows.getAllValues()).extracting(WantListItem::getConditionCode).containsExactly("N", "U");
        assertThat(rows.getAllValues()).extracting(WantListItem::getCompletenessCode).containsExactly("S", "C");
        assertThat(rows.getAllValues()).extracting(WantListItem::getLastConfirmedAt).containsOnly(NOW);
        ArgumentCaptor<CanonicalItem> created = ArgumentCaptor.forClass(CanonicalItem.class);
        verify(canonicalItemDao).insert(created.capture());
        assertThat(created.getValue().getItemTypeCode()).isEqualTo("S");
        assertThat(created.getValue().getItemNumber()).isEqualTo("6541-1");
    }

    @Test
    void anItemWhoseCompletenessHasNoCanonicalCodeIsSkipped() {
        when(marketplaceListingDao.findWantedCatalogItemsByListingExternalServiceIdAndListingStatusCodes(any(), any()))
                .thenReturn(List.of(wanted("6390-1", "U", "BOXED")));

        BricklinkWantListSyncResult result = service.runOnce();

        assertThat(result.itemsSkipped()).isOne();
        assertThat(result.itemsConfirmed()).isZero();
        verify(wantListItemDao, never()).upsert(any());
    }

    @Test
    void anotherEnvironmentCreatingTheSameCanonicalItemFirstIsNotAnError() {
        when(marketplaceListingDao.findWantedCatalogItemsByListingExternalServiceIdAndListingStatusCodes(any(), any()))
                .thenReturn(List.of(wanted("6390-1", "N", "S")));
        when(canonicalItemDao.findByItemTypeCodeAndItemNumber("S", "6390-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(canonical(7, "6390-1")));
        when(canonicalItemDao.insert(any())).thenThrow(new DuplicateKeyException("uq_canonical_item_type_number"));

        BricklinkWantListSyncResult result = service.runOnce();

        assertThat(result.itemsConfirmed()).isOne();
        assertThat(result.canonicalItemsAdded()).isZero();
    }

    @Test
    void refusesToRunWithoutAnEnvironmentCode() {
        properties.setEnvironmentCode(" ");

        assertThatThrownBy(() -> service.runOnce()).hasMessageContaining("environment-code is not set");
    }

    private WantedCatalogItem wanted(String number, String condition, String completeness) {
        return WantedCatalogItem.builder()
                .itemTypeCode("S")
                .itemNumber(number)
                .itemName("Set " + number)
                .conditionCode(condition)
                .completenessCode(completeness)
                .listingCount(1)
                .build();
    }

    private CanonicalItem canonical(int id, String number) {
        return CanonicalItem.builder().canonicalItemId(id).itemTypeCode("S").itemNumber(number).build();
    }
}
