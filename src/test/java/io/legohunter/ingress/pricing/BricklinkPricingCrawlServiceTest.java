package io.legohunter.ingress.pricing;

import com.bricklink.api.ajax.BricklinkAjaxClient;
import com.bricklink.api.ajax.exception.BricklinkAjaxClientException;
import com.bricklink.api.ajax.model.v1.Item;
import com.bricklink.api.ajax.model.v1.ItemForSale;
import com.bricklink.api.ajax.support.CatalogItemsForSaleResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.data.dao.CanonicalItemDao;
import io.legohunter.data.dao.CrawlAttemptDao;
import io.legohunter.data.dao.CrawlTargetDao;
import io.legohunter.data.dao.MarketListingDao;
import io.legohunter.data.dao.MarketSnapshotDao;
import io.legohunter.data.dao.MarketplaceItemDao;
import io.legohunter.data.dto.CanonicalItem;
import io.legohunter.data.dto.CrawlAttempt;
import io.legohunter.data.dto.CrawlTarget;
import io.legohunter.data.dto.MarketListing;
import io.legohunter.data.dto.MarketSnapshot;
import io.legohunter.data.dto.MarketplaceItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The shared BrickLink crawler (HOMELAB-32): queue scheduling, one fetch per item + condition, history, failures. */
class BricklinkPricingCrawlServiceTest {
    /** A Monday, 14:00 in New York: outside the 21:30-08:30 weekday blackout. */
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-11-09T19:00:00Z");

    private BricklinkAjaxClient ajaxClient;
    private CanonicalItemDao canonicalItemDao;
    private MarketplaceItemDao marketplaceItemDao;
    private CrawlTargetDao crawlTargetDao;
    private CrawlAttemptDao crawlAttemptDao;
    private MarketSnapshotDao marketSnapshotDao;
    private MarketListingDao marketListingDao;
    private BricklinkPricingCrawlProperties properties;
    private ZonedDateTime now;
    private BricklinkPricingCrawlService service;

    @BeforeEach
    void setUp() {
        ajaxClient = mock(BricklinkAjaxClient.class);
        canonicalItemDao = mock(CanonicalItemDao.class);
        marketplaceItemDao = mock(MarketplaceItemDao.class);
        crawlTargetDao = mock(CrawlTargetDao.class);
        crawlAttemptDao = mock(CrawlAttemptDao.class);
        marketSnapshotDao = mock(MarketSnapshotDao.class);
        marketListingDao = mock(MarketListingDao.class);
        properties = new BricklinkPricingCrawlProperties();
        properties.setEnabled(true);
        properties.setWorkerBatchSize(5);
        properties.setResultsPerPage(500);
        properties.setClaimedBy("crawler-test");
        now = NOW;

        when(crawlTargetDao.findWantedWithoutTarget("BRICKLINK")).thenReturn(List.of());
        when(crawlTargetDao.findDisabledWanted("BRICKLINK")).thenReturn(List.of());
        when(crawlTargetDao.findDue(eq("BRICKLINK"), any(), anyInt())).thenReturn(List.of());
        when(crawlTargetDao.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(crawlTargetDao.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(crawlAttemptDao.insert(any())).thenAnswer(invocation -> {
            CrawlAttempt attempt = invocation.getArgument(0);
            attempt.setCrawlAttemptId(900L);
            return attempt;
        });
        when(crawlAttemptDao.finish(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketSnapshotDao.insert(any())).thenAnswer(invocation -> {
            MarketSnapshot snapshot = invocation.getArgument(0);
            snapshot.setMarketSnapshotId(500L);
            return snapshot;
        });
        when(marketListingDao.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketplaceItemDao.insert(any())).thenAnswer(invocation -> {
            MarketplaceItem item = invocation.getArgument(0);
            item.setMarketplaceItemId(40);
            return item;
        });
        when(marketplaceItemDao.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(canonicalItemDao.findByCanonicalItemId(7)).thenReturn(Optional.of(
                CanonicalItem.builder().canonicalItemId(7).itemTypeCode("S").itemNumber("6390-1").itemName("Main Street").build()));

        service = new BricklinkPricingCrawlService(ajaxClient, properties, canonicalItemDao, marketplaceItemDao,
                crawlTargetDao, crawlAttemptDao, marketSnapshotDao, marketListingDao, new ObjectMapper()) {
            @Override
            ZonedDateTime now() {
                return now;
            }
        };
    }

    @Test
    void newWantsBecomeTargetsSpreadOverTheScheduleWindow() {
        properties.setScheduleSpreadWindow(Duration.ofHours(72));
        when(crawlTargetDao.findWantedWithoutTarget("BRICKLINK")).thenReturn(List.of(
                CrawlTarget.builder().canonicalItemId(7).queryConditionCode("N").build(),
                CrawlTarget.builder().canonicalItemId(8).queryConditionCode("U").build()));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SCHEDULED");
        assertThat(result.targetsCreated()).isEqualTo(2);
        ArgumentCaptor<CrawlTarget> inserted = ArgumentCaptor.forClass(CrawlTarget.class);
        verify(crawlTargetDao, times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(CrawlTarget::getMarketplaceCode).containsOnly("BRICKLINK");
        assertThat(inserted.getAllValues()).extracting(CrawlTarget::getTargetStatusCode).containsOnly("IDLE");
        assertThat(inserted.getAllValues().get(0).getNextDueAt()).isEqualTo(NOW);
        assertThat(inserted.getAllValues().get(1).getNextDueAt()).isEqualTo(NOW.plusHours(36));
    }

    @Test
    void aTargetWantedAgainIsDueNoEarlierThanACadenceAfterItsLastSuccess() {
        CrawlTarget recentlyCrawled = disabledTarget(10L, NOW.minusDays(2));
        CrawlTarget neverCrawled = disabledTarget(11L, null);
        when(crawlTargetDao.findDisabledWanted("BRICKLINK")).thenReturn(List.of(recentlyCrawled, neverCrawled));
        when(crawlTargetDao.disableUnwanted("BRICKLINK")).thenReturn(3);

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.targetsReenabled()).isEqualTo(2);
        assertThat(result.targetsDisabled()).isEqualTo(3);
        assertThat(recentlyCrawled.getTargetStatusCode()).isEqualTo("IDLE");
        assertThat(recentlyCrawled.getNextDueAt()).isEqualTo(NOW.plusDays(5));
        assertThat(neverCrawled.getNextDueAt()).isEqualTo(NOW);
        verify(crawlTargetDao).update(recentlyCrawled);
        verify(crawlTargetDao).update(neverCrawled);
    }

    @Test
    void oneFetchStoresEverySubConditionWithRawAndCanonicalCodesAndMovesTheTargetACadenceAhead() {
        CrawlTarget target = dueTarget(20L, "N", 0);
        givenClaimed(target);
        givenResolvedMarketplaceItem("4997");
        ItemForSale sealed = lot(3001, "seller-a", "Store A", "US $399.99", "EUR 370.00", "N", "S");
        ItemForSale complete = lot(3002, "seller-b", "Store B", "US $250.00", "US $250.00", "N", "C");
        when(ajaxClient.catalogItemsForSaleByInternalItemId(4997, "N", 500)).thenReturn(result(sealed, complete));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.targetsClaimed()).isOne();
        assertThat(result.snapshotsWritten()).isOne();
        assertThat(result.listingsWritten()).isEqualTo(2);
        assertThat(result.failedFetches()).isZero();

        MarketSnapshot snapshot = captured(marketSnapshotDao);
        assertThat(snapshot.getMarketplaceCode()).isEqualTo("BRICKLINK");
        assertThat(snapshot.getCanonicalItemId()).isEqualTo(7);
        assertThat(snapshot.getQueryConditionCode()).isEqualTo("N");
        assertThat(snapshot.getCrawlTargetId()).isEqualTo(20L);
        assertThat(snapshot.getMarketplaceUniqueKey()).isEqualTo("4997");
        assertThat(snapshot.getListingCount()).isEqualTo(2);
        assertThat(snapshot.getCapturedAt()).isEqualTo(NOW);
        assertThat(snapshot.getRequestParameters()).contains("\"itemid\":4997", "\"cond\":\"N\"");

        ArgumentCaptor<MarketListing> lots = ArgumentCaptor.forClass(MarketListing.class);
        verify(marketListingDao, times(2)).insert(lots.capture());
        MarketListing storedSealed = lots.getAllValues().get(0);
        assertThat(storedSealed.getMarketSnapshotId()).isEqualTo(500L);
        assertThat(storedSealed.getSellerKey()).isEqualTo("seller-a");
        assertThat(storedSealed.getSellerName()).isEqualTo("Store A");
        assertThat(storedSealed.getRawConditionCode()).isEqualTo("N");
        assertThat(storedSealed.getConditionCode()).isEqualTo("N");
        assertThat(storedSealed.getCompletenessCode()).isEqualTo("S");
        assertThat(storedSealed.getItemPrice()).isEqualByComparingTo("399.99");
        assertThat(storedSealed.getCurrencyCode()).isEqualTo("USD");
        assertThat(storedSealed.getNativePrice()).isEqualByComparingTo("370.00");
        assertThat(storedSealed.getNativeCurrencyCode()).isEqualTo("EUR");
        assertThat(storedSealed.getMatchConfidence()).isEqualByComparingTo("1.0000");
        assertThat(storedSealed.getListingFormatCode()).isEqualTo("FIXED_PRICE");
        assertThat(storedSealed.getShippingPrice()).isNull();
        MarketListing storedComplete = lots.getAllValues().get(1);
        assertThat(storedComplete.getCompletenessCode()).isEqualTo("C");
        assertThat(storedComplete.getNativePrice()).as("same currency as the display price").isNull();

        CrawlAttempt attempt = finishedAttempt();
        assertThat(attempt.getOutcomeCode()).isEqualTo("SUCCEEDED");
        assertThat(attempt.getMarketSnapshotId()).isEqualTo(500L);
        assertThat(target.getTargetStatusCode()).isEqualTo("IDLE");
        assertThat(target.getClaimedBy()).isNull();
        assertThat(target.getLastSuccessAt()).isEqualTo(NOW);
        assertThat(target.getLastMarketSnapshotId()).isEqualTo(500L);
        assertThat(target.getConsecutiveFailureCount()).isZero();
        assertThat(target.getNextDueAt()).isEqualTo(NOW.plusDays(7));
    }

    @Test
    void anEmptyMarketIsStillASnapshot() {
        CrawlTarget target = dueTarget(20L, "U", 0);
        givenClaimed(target);
        givenResolvedMarketplaceItem("4997");
        when(ajaxClient.catalogItemsForSaleByInternalItemId(4997, "U", 500)).thenReturn(result());

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.snapshotsWritten()).isOne();
        assertThat(result.emptySnapshots()).isOne();
        assertThat(captured(marketSnapshotDao).getListingCount()).isZero();
        verify(marketListingDao, never()).insert(any());
    }

    @Test
    void theIdItemIsResolvedOnceAndKeptOnTheMarketplaceItem() {
        CrawlTarget target = dueTarget(20L, "N", 0);
        givenClaimed(target);
        when(marketplaceItemDao.findByMarketplaceCodeAndCanonicalItemId("BRICKLINK", 7)).thenReturn(Optional.empty());
        when(ajaxClient.findCatalogItem("6390-1", "S")).thenReturn(Optional.of(item(4997)));
        when(ajaxClient.catalogItemsForSaleByInternalItemId(4997, "N", 500)).thenReturn(result());

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.idItemsResolved()).isOne();
        ArgumentCaptor<MarketplaceItem> created = ArgumentCaptor.forClass(MarketplaceItem.class);
        verify(marketplaceItemDao).insert(created.capture());
        assertThat(created.getValue().getMarketplaceItemKey()).isEqualTo("6390-1");
        assertThat(created.getValue().getMatchMethodCode()).isEqualTo("CATALOG_ID");
        ArgumentCaptor<MarketplaceItem> updated = ArgumentCaptor.forClass(MarketplaceItem.class);
        verify(marketplaceItemDao).update(updated.capture());
        assertThat(updated.getValue().getMarketplaceUniqueKey()).isEqualTo("4997");
        assertThat(updated.getValue().getResolvedAt()).isEqualTo(NOW);
    }

    @Test
    void aFailedFetchIsLoggedAndRetriedAfterTheBackoff() {
        properties.setRetryBackoff(Duration.ofHours(6));
        CrawlTarget target = dueTarget(20L, "N", 0);
        givenClaimed(target);
        givenResolvedMarketplaceItem("4997");
        when(ajaxClient.catalogItemsForSaleByInternalItemId(4997, "N", 500))
                .thenThrow(new IllegalStateException("302 to maintenance.bricklink.com"));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.failedFetches()).isOne();
        assertThat(result.snapshotsWritten()).isZero();
        CrawlAttempt attempt = finishedAttempt();
        assertThat(attempt.getOutcomeCode()).isEqualTo("FAILED_HTTP");
        assertThat(attempt.getErrorMessage()).contains("maintenance");
        assertThat(target.getConsecutiveFailureCount()).isOne();
        assertThat(target.getLastErrorMessage()).contains("maintenance");
        assertThat(target.getTargetStatusCode()).isEqualTo("IDLE");
        assertThat(target.getNextDueAt()).isEqualTo(NOW.plusHours(6));
        verify(marketSnapshotDao, never()).insert(any());
    }

    @Test
    void afterMaxAttemptsFailuresInARowTheTargetWaitsAFullCadence() {
        properties.setMaxAttempts(3);
        CrawlTarget target = dueTarget(20L, "N", 2);
        givenClaimed(target);
        givenResolvedMarketplaceItem("4997");
        when(ajaxClient.catalogItemsForSaleByInternalItemId(4997, "N", 500)).thenThrow(new IllegalStateException("blocked"));

        service.runOnce();

        assertThat(target.getConsecutiveFailureCount()).isEqualTo(3);
        assertThat(target.getNextDueAt()).isEqualTo(NOW.plusDays(7));
    }

    @Test
    void anItemBrickLinkCannotFindFailsWithoutAFetch() {
        CrawlTarget target = dueTarget(20L, "N", 0);
        givenClaimed(target);
        givenResolvedMarketplaceItem(null);
        when(ajaxClient.findCatalogItem("6390-1", "S")).thenReturn(Optional.empty());

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.failedFetches()).isOne();
        assertThat(finishedAttempt().getOutcomeCode()).isEqualTo("FAILED_RESOLVE_NO_MATCH");
        verify(ajaxClient, never()).catalogItemsForSaleByInternalItemId(any(), anyString(), anyInt());
    }

    @Test
    void anAmbiguousItemIsRecordedAsSuch() {
        CrawlTarget target = dueTarget(20L, "N", 0);
        givenClaimed(target);
        givenResolvedMarketplaceItem(null);
        when(ajaxClient.findCatalogItem("6390-1", "S")).thenThrow(new BricklinkAjaxClientException("Ambiguous match for 6390-1"));

        service.runOnce();

        assertThat(finishedAttempt().getOutcomeCode()).isEqualTo("FAILED_RESOLVE_AMBIGUOUS");
    }

    @Test
    void aTargetAnotherCrawlerClaimedFirstIsLeftAlone() {
        CrawlTarget target = dueTarget(20L, "N", 0);
        when(crawlTargetDao.findDue(eq("BRICKLINK"), any(), anyInt())).thenReturn(List.of(target));
        when(crawlTargetDao.claim(eq(20L), eq("crawler-test"), any(), any())).thenReturn(false);

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_WORK");
        verify(crawlAttemptDao, never()).insert(any());
        verify(ajaxClient, never()).catalogItemsForSaleByInternalItemId(any(), anyString(), anyInt());
    }

    @Test
    void duringTheBlackoutTheQueueIsKeptButNothingIsFetched() {
        enableBlackout();
        now = ZonedDateTime.parse("2026-11-10T04:00:00Z"); // Monday 23:00 in New York
        when(crawlTargetDao.findWantedWithoutTarget("BRICKLINK")).thenReturn(List.of(
                CrawlTarget.builder().canonicalItemId(7).queryConditionCode("N").build()));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("BLACKOUT");
        assertThat(result.targetsCreated()).isOne();
        verify(crawlTargetDao, never()).findDue(any(), any(), anyInt());
        ArgumentCaptor<CrawlTarget> inserted = ArgumentCaptor.forClass(CrawlTarget.class);
        verify(crawlTargetDao).insert(inserted.capture());
        // A new target never becomes due inside the blackout: it moves to 08:30 New York.
        assertThat(inserted.getValue().getNextDueAt()).isEqualTo(ZonedDateTime.parse("2026-11-10T13:30:00Z"));
    }

    @Test
    void theBlackoutIsWeekdaysOnly() {
        enableBlackout();
        assertThat(service.isInBlackout(ZonedDateTime.parse("2026-11-10T04:00:00Z"))).isTrue();   // Mon 23:00
        assertThat(service.isInBlackout(ZonedDateTime.parse("2026-11-10T12:00:00Z"))).isTrue();   // Tue 07:00
        assertThat(service.isInBlackout(ZonedDateTime.parse("2026-11-10T14:00:00Z"))).isFalse();  // Tue 09:00
        assertThat(service.isInBlackout(ZonedDateTime.parse("2026-11-15T04:00:00Z"))).isFalse();  // Sat 23:00
    }

    @Test
    void readsTheSellersOwnCurrencyPrice() {
        assertThat(BricklinkPricingCrawlService.nativePrice("EUR 315.00"))
                .contains(new BricklinkPricingCrawlService.NativePrice(new BigDecimal("315.00"), "EUR"));
        assertThat(BricklinkPricingCrawlService.nativePrice("CA $412.50"))
                .contains(new BricklinkPricingCrawlService.NativePrice(new BigDecimal("412.50"), "CAD"));
        assertThat(BricklinkPricingCrawlService.nativePrice("HUF 4,987.00"))
                .contains(new BricklinkPricingCrawlService.NativePrice(new BigDecimal("4987.00"), "HUF"));
        assertThat(BricklinkPricingCrawlService.nativePrice(null)).isEmpty();
        assertThat(BricklinkPricingCrawlService.nativePrice("free")).isEmpty();
    }

    @Test
    void nothingToDoIsNoWork() {
        assertThat(service.runOnce().outcome()).isEqualTo("NO_WORK");
        verify(crawlTargetDao, atLeastOnce()).disableUnwanted("BRICKLINK");
    }

    private void enableBlackout() {
        BricklinkPricingCrawlProperties.Blackout blackout = new BricklinkPricingCrawlProperties.Blackout();
        blackout.setEnabled(true);
        blackout.setZoneId("America/New_York");
        blackout.setStart(LocalTime.of(21, 30));
        blackout.setEnd(LocalTime.of(8, 30));
        blackout.setWeekdaysOnly(true);
        properties.setBlackout(blackout);
    }

    private CrawlTarget dueTarget(Long id, String condition, int failures) {
        return CrawlTarget.builder()
                .crawlTargetId(id)
                .marketplaceCode("BRICKLINK")
                .canonicalItemId(7)
                .queryConditionCode(condition)
                .targetStatusCode("CLAIMED")
                .claimedBy("crawler-test")
                .nextDueAt(NOW.minusMinutes(5))
                .consecutiveFailureCount(failures)
                .build();
    }

    private CrawlTarget disabledTarget(Long id, ZonedDateTime lastSuccessAt) {
        return CrawlTarget.builder()
                .crawlTargetId(id)
                .marketplaceCode("BRICKLINK")
                .canonicalItemId(7)
                .queryConditionCode("N")
                .targetStatusCode("DISABLED")
                .lastSuccessAt(lastSuccessAt)
                .nextDueAt(NOW.minusDays(30))
                .build();
    }

    private void givenClaimed(CrawlTarget target) {
        when(crawlTargetDao.findDue(eq("BRICKLINK"), any(), anyInt())).thenReturn(List.of(target));
        when(crawlTargetDao.claim(eq(target.getCrawlTargetId()), eq("crawler-test"), any(), any())).thenReturn(true);
        when(crawlTargetDao.findByCrawlTargetId(target.getCrawlTargetId())).thenReturn(Optional.of(target));
    }

    private void givenResolvedMarketplaceItem(String idItem) {
        when(marketplaceItemDao.findByMarketplaceCodeAndCanonicalItemId("BRICKLINK", 7)).thenReturn(Optional.of(MarketplaceItem.builder()
                .marketplaceItemId(40)
                .marketplaceCode("BRICKLINK")
                .canonicalItemId(7)
                .marketplaceItemKey("6390-1")
                .marketplaceUniqueKey(idItem)
                .matchMethodCode("CATALOG_ID")
                .build()));
    }

    private MarketSnapshot captured(MarketSnapshotDao dao) {
        ArgumentCaptor<MarketSnapshot> captor = ArgumentCaptor.forClass(MarketSnapshot.class);
        verify(dao).insert(captor.capture());
        return captor.getValue();
    }

    private CrawlAttempt finishedAttempt() {
        ArgumentCaptor<CrawlAttempt> captor = ArgumentCaptor.forClass(CrawlAttempt.class);
        verify(crawlAttemptDao).finish(captor.capture());
        assertThat(captor.getValue().getFinishedAt()).isNotNull();
        return captor.getValue();
    }

    private Item item(Integer idItem) {
        Item item = new Item();
        item.setIdItem(idItem);
        item.setTypeItem("S");
        item.setStrItemNo("6390-1");
        item.setStrItemName("Main Street");
        return item;
    }

    private CatalogItemsForSaleResult result(ItemForSale... lots) {
        CatalogItemsForSaleResult result = new CatalogItemsForSaleResult();
        result.setTotal_count(lots.length);
        result.setRpp(500);
        result.setPi(1);
        result.setList(List.of(lots));
        return result;
    }

    private ItemForSale lot(Integer idInv, String seller, String store, String displayPrice, String nativePrice,
                            String condition, String completeness) {
        ItemForSale lot = new ItemForSale();
        lot.setIdInv(idInv);
        lot.setStrSellerUsername(seller);
        lot.setStrStorename(store);
        lot.setStrSellerCountryCode("US");
        lot.setN4SellerFeedbackScore(120);
        lot.setCodeNew(condition);
        lot.setCodeComplete(completeness);
        lot.setN4Qty(1);
        lot.setMDisplaySalePrice(displayPrice);
        lot.setMInvSalePrice(nativePrice);
        lot.setStrDesc("Main Street lot");
        return lot;
    }
}
