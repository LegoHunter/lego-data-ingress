package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dao.PricingApplyReadinessDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.data.dto.PricingApplyReadinessReview;
import io.legohunter.data.dto.PricingDecision;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingApplyServiceTest {
    private final BricklinkPricingApplyProperties properties = new BricklinkPricingApplyProperties();
    private final PricingApplyReadinessDao pricingApplyReadinessDao = mock(PricingApplyReadinessDao.class);
    private final PricingDecisionDao pricingDecisionDao = mock(PricingDecisionDao.class);
    private final MarketplaceListingDao marketplaceListingDao = mock(MarketplaceListingDao.class);
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao = mock(BricklinkMarketplaceListingDao.class);
    private final MarketplaceListingSyncRequestDao marketplaceListingSyncRequestDao = mock(MarketplaceListingSyncRequestDao.class);
    private final BricklinkMarketplaceSyncProperties marketplaceSyncProperties = new BricklinkMarketplaceSyncProperties();
    private final CountingTransactions transactions = new CountingTransactions();
    private final BricklinkPricingApplyService service = new BricklinkPricingApplyService(
            properties,
            pricingApplyReadinessDao,
            pricingDecisionDao,
            marketplaceListingDao,
            bricklinkMarketplaceListingDao,
            marketplaceListingSyncRequestDao,
            marketplaceSyncProperties,
            transactions
    );

    @Test
    void dryRunSelectsReadyRowsWithoutMutatingAnything() {
        properties.setMode("DRY_RUN");
        arrangeReadyCandidate();

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.mode()).isEqualTo(BricklinkPricingApplyMode.DRY_RUN);
        assertThat(result.dryRunSelections()).isOne();
        assertThat(result.localPricesUpdated()).isZero();
        verifyNothingWritten();
    }

    @Test
    void priceUpdateAcceptsTheDecisionAndQueuesTheSyncWithoutTouchingTheLocalPrice() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        properties.setEnvironmentCode("sandbox");
        arrangeReadyCandidate();
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(bricklinkListing()));
        arrangeDecisionAccepted();

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.localPricesUpdated()).isZero();
        assertThat(result.syncRequestsEnqueued()).isOne();
        assertThat(transactions.executions).isOne();
        // Remote first: the local price waits for BrickLink to confirm it.
        verify(marketplaceListingDao, never()).updateUnitPrice(any(), any(), any());
        verify(pricingDecisionDao, never()).markApplied(any(), any());
        verify(pricingDecisionDao).transitionStatus(500L, "PROPOSED", "ACCEPTED", null);
        MarketplaceListingSyncRequest request = insertedRequest();
        assertThat(request.getSyncRequestTypeCode()).isEqualTo("PRICE_UPDATE");
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("PENDING");
        assertThat(request.getPreviousUnitPrice()).isEqualByComparingTo("225.00");
        assertThat(request.getRequestedUnitPrice()).isEqualByComparingTo("219.00");
        assertThat(request.getRemoteInventoryId()).isEqualTo("12345");
        assertThat(request.getAppliedLocalAt()).isNull();
        verify(marketplaceListingSyncRequestDao, never()).upsert(any());
    }

    @Test
    void priceUpdateWaitsWhileAnEarlierSyncRequestForTheListingIsStillOpen() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        arrangeReadyCandidate();
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(bricklinkListing()));
        when(marketplaceListingSyncRequestDao.findByMarketplaceListingId(100)).thenReturn(Set.of(
                MarketplaceListingSyncRequest.builder().marketplaceListingSyncRequestId(1L).syncRequestStatusCode("SUCCEEDED").build(),
                MarketplaceListingSyncRequest.builder().marketplaceListingSyncRequestId(2L).syncRequestStatusCode("CLAIMED").build()
        ));

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.skippedRows()).isOne();
        assertThat(result.failedRows()).isZero();
        assertThat(result.syncRequestsEnqueued()).isZero();
        verifyNothingWritten();
    }

    @Test
    void priceUpdateProceedsWhenEarlierSyncRequestsHaveFinished() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        arrangeReadyCandidate();
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(bricklinkListing()));
        when(marketplaceListingSyncRequestDao.findByMarketplaceListingId(100)).thenReturn(Set.of(
                MarketplaceListingSyncRequest.builder().marketplaceListingSyncRequestId(1L).syncRequestStatusCode("SUCCEEDED").build(),
                MarketplaceListingSyncRequest.builder().marketplaceListingSyncRequestId(2L).syncRequestStatusCode("BLOCKED").build(),
                MarketplaceListingSyncRequest.builder().marketplaceListingSyncRequestId(3L).syncRequestStatusCode("FAILED").build()
        ));
        arrangeDecisionAccepted();

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.syncRequestsEnqueued()).isOne();
        assertThat(insertedRequest().getSyncRequestTypeCode()).isEqualTo("PRICE_UPDATE");
    }

    @Test
    void priceUpdateSkipsADecisionAnotherWorkerAcceptedFirst() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        arrangeReadyCandidate();
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(bricklinkListing()));
        when(pricingDecisionDao.transitionStatus(500L, "PROPOSED", "ACCEPTED", null)).thenReturn(Optional.empty());

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.skippedRows()).isOne();
        assertThat(result.failedRows()).isZero();
        assertThat(result.syncRequestsEnqueued()).isZero();
        verify(marketplaceListingSyncRequestDao, never()).insert(any());
    }

    @Test
    void priceUpdateNeverOverwritesAnExistingSyncRequestForTheSameDecision() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        arrangeReadyCandidate();
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(bricklinkListing()));
        arrangeDecisionAccepted();
        when(marketplaceListingSyncRequestDao.findByMarketplaceListingIdAndPricingDecisionIdAndSyncRequestTypeCode(100, 500L, "PRICE_UPDATE"))
                .thenReturn(Optional.of(MarketplaceListingSyncRequest.builder().marketplaceListingSyncRequestId(9L).syncRequestStatusCode("SUCCEEDED").build()));

        BricklinkPricingApplyResult result = service.runOnce();

        // The transaction throws, so the ACCEPTED marker rolls back with it.
        assertThat(result.failedRows()).isOne();
        assertThat(result.outcome()).isEqualTo("FAILED");
        verify(marketplaceListingSyncRequestDao, never()).insert(any());
        verify(marketplaceListingSyncRequestDao, never()).upsert(any());
        verify(marketplaceListingDao, never()).updateUnitPrice(any(), any(), any());
    }

    @Test
    void activeListingWithoutABricklinkLotIsSkippedAndKeepsItsPrice() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        arrangeReadyCandidate(listing("ACTIVE"));
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.empty());

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.failedRows()).isZero();
        assertThat(result.skippedRows()).isOne();
        assertThat(result.localPricesUpdated()).isZero();
        assertThat(result.syncRequestsEnqueued()).isZero();
        verifyNothingWritten();
    }

    @Test
    void pricedDraftGetsTheNewDraftPriceAndAListingCreateRequestInOneTransaction() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        marketplaceSyncProperties.setEnvironmentCode("sandbox");
        marketplaceSyncProperties.setProduction(false);
        marketplaceSyncProperties.setNonProdStockRoomId("C");
        arrangeReadyCandidate(listing("DRAFT"));
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(bricklinkDraftMapping()));
        when(marketplaceListingDao.updateUnitPrice(eq(100), eq(new BigDecimal("219.00")), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(listing("DRAFT")));
        arrangeDecisionAccepted();

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.localPricesUpdated()).isOne();
        assertThat(result.syncRequestsEnqueued()).isOne();
        assertThat(transactions.executions).isOne();
        verify(pricingDecisionDao).transitionStatus(500L, "PROPOSED", "ACCEPTED", null);
        MarketplaceListingSyncRequest request = insertedRequest();
        assertThat(request.getSyncRequestTypeCode()).isEqualTo("LISTING_CREATE");
        assertThat(request.getRemoteVisibilityContainerId()).isEqualTo("C");
        assertThat(request.getAppliedLocalAt()).isNotNull();
    }

    @Test
    void unpricedDraftIsPricedBeforeCreatingTheBricklinkListing() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        marketplaceSyncProperties.setEnvironmentCode("sandbox");
        marketplaceSyncProperties.setProduction(false);
        marketplaceSyncProperties.setNonProdStockRoomId("C");
        PricingApplyReadinessReview initialReview = review();
        initialReview.setReadinessStatusCode("READY_TO_APPLY_INITIAL_PRICE");
        initialReview.setCurrentPrice(null);
        MarketplaceListing unpricedDraft = listing("DRAFT");
        unpricedDraft.setUnitPrice(null);
        arrangeReadyCandidate(initialReview, unpricedDraft);
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(bricklinkDraftMapping()));
        when(marketplaceListingDao.updateUnitPrice(eq(100), eq(new BigDecimal("219.00")), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(listing("DRAFT")));
        arrangeDecisionAccepted();

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.failedRows()).isZero();
        assertThat(result.localPricesUpdated()).isOne();
        assertThat(result.syncRequestsEnqueued()).isOne();
        MarketplaceListingSyncRequest request = insertedRequest();
        assertThat(request.getSyncRequestTypeCode()).isEqualTo("LISTING_CREATE");
        assertThat(request.getSyncReasonCode()).isEqualTo("INITIAL_PRICE_APPLIED");
        assertThat(request.getPreviousUnitPrice()).isNull();
        assertThat(request.getRequestedUnitPrice()).isEqualByComparingTo("219.00");
    }

    @Test
    void initialPriceReadinessIsRefusedForAListingThatAlreadyHasALot() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        PricingApplyReadinessReview initialReview = review();
        initialReview.setReadinessStatusCode("READY_TO_APPLY_INITIAL_PRICE");
        MarketplaceListing unpricedDraft = listing("DRAFT");
        unpricedDraft.setUnitPrice(null);
        arrangeReadyCandidate(initialReview, unpricedDraft);
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(bricklinkListing()));

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.failedRows()).isOne();
        verifyNothingWritten();
    }

    @Test
    void applyLocalOnlyChangesTheLocalPriceAndMarksTheDecisionAppliedWithoutASyncRequest() {
        properties.setMode("APPLY_LOCAL_ONLY");
        arrangeReadyCandidate();
        when(marketplaceListingDao.updateUnitPrice(eq(100), eq(new BigDecimal("219.00")), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(listing()));
        when(pricingDecisionDao.transitionStatus(eq(500L), eq("PROPOSED"), eq("APPLIED"), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(decision()));

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.localPricesUpdated()).isOne();
        assertThat(result.syncRequestsEnqueued()).isZero();
        assertThat(transactions.executions).isOne();
        verify(marketplaceListingSyncRequestDao, never()).insert(any());
    }

    @Test
    void runOnceSkipsAlreadyAppliedPricingDecisionWithoutFailingRow() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        when(pricingApplyReadinessDao.findLatestReadyToApplyReviews(25)).thenReturn(Set.of(review()));
        when(marketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(listing()));
        when(pricingDecisionDao.findByPricingDecisionId(500L)).thenReturn(Optional.of(appliedDecision()));

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.skippedRows()).isOne();
        assertThat(result.failedRows()).isZero();
        assertThat(result.localPricesUpdated()).isZero();
        assertThat(result.syncRequestsEnqueued()).isZero();
        verifyNothingWritten();
    }

    @Test
    void runOnceFailsARowWhoseDecisionIsNoLongerProposed() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        when(pricingApplyReadinessDao.findLatestReadyToApplyReviews(25)).thenReturn(Set.of(review()));
        when(marketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(listing()));
        PricingDecision accepted = decision();
        accepted.setDecisionStatusCode("ACCEPTED");
        when(pricingDecisionDao.findByPricingDecisionId(500L)).thenReturn(Optional.of(accepted));

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.failedRows()).isOne();
        verifyNothingWritten();
    }

    private void arrangeDecisionAccepted() {
        PricingDecision accepted = decision();
        accepted.setDecisionStatusCode("ACCEPTED");
        when(pricingDecisionDao.transitionStatus(500L, "PROPOSED", "ACCEPTED", null)).thenReturn(Optional.of(accepted));
        when(marketplaceListingSyncRequestDao.insert(any(MarketplaceListingSyncRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private MarketplaceListingSyncRequest insertedRequest() {
        ArgumentCaptor<MarketplaceListingSyncRequest> captor = ArgumentCaptor.forClass(MarketplaceListingSyncRequest.class);
        verify(marketplaceListingSyncRequestDao).insert(captor.capture());
        return captor.getValue();
    }

    private void verifyNothingWritten() {
        verify(marketplaceListingDao, never()).updateUnitPrice(any(), any(), any());
        verify(pricingDecisionDao, never()).markApplied(any(), any());
        verify(pricingDecisionDao, never()).transitionStatus(any(), any(), any(), any());
        verify(marketplaceListingSyncRequestDao, never()).insert(any());
        verify(marketplaceListingSyncRequestDao, never()).upsert(any());
    }

    private void arrangeReadyCandidate() {
        arrangeReadyCandidate(listing());
    }

    private void arrangeReadyCandidate(MarketplaceListing listing) {
        arrangeReadyCandidate(review(), listing);
    }

    private void arrangeReadyCandidate(PricingApplyReadinessReview readinessReview, MarketplaceListing listing) {
        when(pricingApplyReadinessDao.findLatestReadyToApplyReviews(25)).thenReturn(Set.of(readinessReview));
        when(marketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(listing));
        when(pricingDecisionDao.findByPricingDecisionId(500L)).thenReturn(Optional.of(decision()));
    }

    private PricingApplyReadinessReview review() {
        return PricingApplyReadinessReview.builder()
                .pricingApplyReadinessId(700L)
                .marketplaceListingId(100)
                .pricingDecisionId(500L)
                .readinessStatusCode("READY_TO_APPLY")
                .decisionReasonCode("MARKET_POSITION")
                .currentPrice(new BigDecimal("225.00"))
                .proposedPrice(new BigDecimal("219.00"))
                .currencyCode("USD")
                .fixedPrice(false)
                .build();
    }

    private MarketplaceListing listing() {
        return listing("ACTIVE");
    }

    private MarketplaceListing listing(String statusCode) {
        return MarketplaceListing.builder()
                .marketplaceListingId(100)
                .itemInventoryId(200)
                .listingExternalServiceId(2)
                .listingStatusCode(statusCode)
                .unitPrice(new BigDecimal("225.00"))
                .currencyCode("USD")
                .fixedPrice(false)
                .build();
    }

    private PricingDecision decision() {
        return PricingDecision.builder()
                .pricingDecisionId(500L)
                .marketplaceListingId(100)
                .decisionStatusCode("PROPOSED")
                .finalPrice(new BigDecimal("219.00"))
                .currencyCode("USD")
                .build();
    }

    private PricingDecision appliedDecision() {
        PricingDecision decision = decision();
        decision.setAppliedAt(ZonedDateTime.parse("2026-07-29T12:00:00Z"));
        return decision;
    }

    private BricklinkMarketplaceListing bricklinkListing() {
        return BricklinkMarketplaceListing.builder()
                .marketplaceListingId(100)
                .bricklinkInventoryId(12345)
                .isStockRoom(true)
                .stockRoomId("A")
                .build();
    }

    private BricklinkMarketplaceListing bricklinkDraftMapping() {
        return BricklinkMarketplaceListing.builder()
                .marketplaceListingId(100)
                .isStockRoom(true)
                .stockRoomId("C")
                .build();
    }
}
