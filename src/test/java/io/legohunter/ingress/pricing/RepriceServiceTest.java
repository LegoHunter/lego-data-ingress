package io.legohunter.ingress.pricing;

import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.data.dto.PricingDecision;
import io.legohunter.ingress.marketplace.MarketplaceSyncPort;
import io.legohunter.ingress.marketplace.bricklink.BricklinkMarketplaceSyncProperties;
import io.legohunter.ingress.marketplace.bricklink.CountingTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepriceServiceTest {
    private final RepriceProperties properties = new RepriceProperties();
    private final BricklinkPricingDecisionProperties decisionProperties = new BricklinkPricingDecisionProperties();
    private final BricklinkPricingApplyReadinessProperties guardProperties = new BricklinkPricingApplyReadinessProperties();
    private final BricklinkMarketplaceSyncProperties syncProperties = new BricklinkMarketplaceSyncProperties();
    private final BricklinkPricingDecider decider = mock(BricklinkPricingDecider.class);
    private final MarketplaceListingDao marketplaceListingDao = mock(MarketplaceListingDao.class);
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao = mock(BricklinkMarketplaceListingDao.class);
    private final PricingDecisionDao pricingDecisionDao = mock(PricingDecisionDao.class);
    private final MarketplaceListingSyncRequestDao syncRequestDao = mock(MarketplaceListingSyncRequestDao.class);
    private final MarketplaceSyncPort port = mock(MarketplaceSyncPort.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MarketplaceSyncPort> ports = mock(ObjectProvider.class);
    private final CountingTransactions transactions = new CountingTransactions();
    private final AtomicLong ids = new AtomicLong(500);

    private RepriceService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setBatchSize(10);
        decisionProperties.setRequireCurrentSnapshot(true);
        syncProperties.setEnvironmentCode("sandbox");
        syncProperties.setNonProdStockRoomId("C");
        when(pricingDecisionDao.insert(any())).thenAnswer(invocation -> {
            PricingDecision decision = invocation.getArgument(0);
            decision.setPricingDecisionId(ids.incrementAndGet());
            return decision;
        });
        when(syncRequestDao.insert(any())).thenAnswer(invocation -> {
            MarketplaceListingSyncRequest request = invocation.getArgument(0);
            request.setMarketplaceListingSyncRequestId(ids.incrementAndGet() + 1000);
            return request;
        });
        when(syncRequestDao.findByMarketplaceListingIdAndPricingDecisionIdAndSyncRequestTypeCode(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(marketplaceListingDao.updateUnitPrice(anyInt(), any(), any())).thenReturn(Optional.of(new MarketplaceListing()));
        when(ports.orderedStream()).thenAnswer(invocation -> Stream.of(port));
        when(port.handles(2)).thenReturn(true);
        when(port.processNow(anyLong())).thenReturn(new MarketplaceSyncPort.HandOff(true, "SUCCEEDED"));
        service = new RepriceService(properties, decisionProperties, guardProperties, syncProperties, decider,
                marketplaceListingDao, bricklinkMarketplaceListingDao, pricingDecisionDao, syncRequestDao, ports, transactions);
    }

    @Test
    void noCandidatesIsNoWork() {
        candidates();

        RepriceResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_WORK");
        verify(pricingDecisionDao, never()).insert(any());
    }

    @Test
    void selectsCandidatesLikeTheDecisionJobWithTheRepriceBatchSize() {
        candidates();

        service.runOnce();

        verify(marketplaceListingDao).findPricingDecisionCandidatesByListingExternalServiceIdAndListingStatusCodes(
                2, Set.of("ACTIVE", "DRAFT"), 10, true);
    }

    @Test
    void dryRunRecordsAPassingDecisionAsProposedWithTheGuardOutcomeAndQueuesNothing() {
        MarketplaceListing listing = active(100, "200.00");
        candidates(listing);
        decides(listing, "210.00");
        hasLot(100);

        RepriceResult result = service.runOnce();

        assertThat(result.proposed()).isOne();
        PricingDecision saved = savedDecision();
        assertThat(saved.getDecisionStatusCode()).isEqualTo("PROPOSED");
        assertThat(saved.getGuardStatusCode()).isEqualTo("PASSED");
        assertThat(saved.getGuardReasonCode()).isNull();
        verify(syncRequestDao, never()).insert(any());
        verify(marketplaceListingDao, never()).updateUnitPrice(anyInt(), any(), any());
        verify(port, never()).processNow(anyLong());
    }

    @Test
    void aGuardRefusalIsRecordedAsBlockedWithItsReasonAndNothingIsSent() {
        properties.setMode("APPLY");
        MarketplaceListing listing = active(100, "200.00");
        candidates(listing);
        decides(listing, "200.00");
        hasLot(100);

        RepriceResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        PricingDecision saved = savedDecision();
        assertThat(saved.getDecisionStatusCode()).isEqualTo("BLOCKED");
        assertThat(saved.getGuardStatusCode()).isEqualTo("BLOCKED");
        assertThat(saved.getGuardReasonCode()).isEqualTo("PRICE_UNCHANGED");
        verify(syncRequestDao, never()).insert(any());
        verify(port, never()).processNow(anyLong());
    }

    @Test
    void skippedAndFailedDecisionsAreSavedWithoutAGuardOutcome() {
        properties.setMode("APPLY");
        MarketplaceListing fixed = active(100, "499.00");
        MarketplaceListing noData = active(101, "50.00");
        candidates(fixed, noData);
        when(decider.decide(fixed)).thenReturn(PricingDecision.builder().marketplaceListingId(100)
                .decisionStatusCode("SKIPPED").reasonCode("FIXED_PRICE_OVERRIDE").finalPrice(new BigDecimal("499.00")).currencyCode("USD").build());
        when(decider.decide(noData)).thenReturn(PricingDecision.builder().marketplaceListingId(101)
                .decisionStatusCode("FAILED").reasonCode("NO_CURRENT_SNAPSHOT").currencyCode("USD").build());

        RepriceResult result = service.runOnce();

        assertThat(result.skipped()).isOne();
        assertThat(result.failed()).isOne();
        ArgumentCaptor<PricingDecision> captor = ArgumentCaptor.forClass(PricingDecision.class);
        verify(pricingDecisionDao, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(decision -> assertThat(decision.getGuardStatusCode()).isNull());
        verify(syncRequestDao, never()).insert(any());
    }

    @Test
    void applyAcceptsQueuesAPriceUpdateInOneTransactionAndHandsItOffAfterTheCommit() {
        properties.setMode("APPLY");
        MarketplaceListing listing = active(100, "200.00");
        candidates(listing);
        decides(listing, "210.00");
        hasLot(100);

        RepriceResult result = service.runOnce();

        assertThat(result.accepted()).isOne();
        assertThat(result.handedOff()).isOne();
        assertThat(result.syncConfirmed()).isOne();
        assertThat(transactions.executions).isOne();
        PricingDecision saved = savedDecision();
        assertThat(saved.getDecisionStatusCode()).isEqualTo("ACCEPTED");
        assertThat(saved.getGuardStatusCode()).isEqualTo("PASSED");
        MarketplaceListingSyncRequest request = insertedRequest();
        assertThat(request.getSyncRequestTypeCode()).isEqualTo("PRICE_UPDATE");
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("PENDING");
        assertThat(request.getPricingDecisionId()).isEqualTo(saved.getPricingDecisionId());
        assertThat(request.getPricingApplyReadinessId()).isNull();
        assertThat(request.getRequestedUnitPrice()).isEqualByComparingTo("210.00");
        assertThat(request.getPreviousUnitPrice()).isEqualByComparingTo("200.00");
        assertThat(request.getRemoteInventoryId()).isEqualTo("12345");
        assertThat(request.getRemoteVisibilityScopeCode()).isEqualTo("STOCKROOM");
        assertThat(request.getRemoteVisibilityContainerId()).isEqualTo("C");
        assertThat(request.getEnvironmentCode()).isEqualTo("sandbox");
        assertThat(request.getCreatedByJobName()).isEqualTo("RepriceJob");
        assertThat(request.getSyncReasonCode()).isEqualTo("PRICING_DECISION_APPLIED");
        // Remote first: the local price waits for the marketplace to confirm it.
        verify(marketplaceListingDao, never()).updateUnitPrice(anyInt(), any(), any());
        // Decision before request (the request references it), hand-off last.
        InOrder order = inOrder(pricingDecisionDao, syncRequestDao, port);
        order.verify(pricingDecisionDao).insert(any());
        order.verify(syncRequestDao).insert(any());
        order.verify(port).processNow(request.getMarketplaceListingSyncRequestId());
    }

    @Test
    void anUnpricedDraftGetsItsFirstPriceAndAStockroomListingCreate() {
        properties.setMode("APPLY");
        MarketplaceListing draft = MarketplaceListing.builder().marketplaceListingId(101).listingStatusCode("DRAFT")
                .currencyCode("USD").fixedPrice(false).build();
        candidates(draft);
        decides(draft, "150.00");
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(101)).thenReturn(Optional.empty());

        RepriceResult result = service.runOnce();

        assertThat(result.accepted()).isOne();
        verify(marketplaceListingDao).updateUnitPrice(eq(101), eq(new BigDecimal("150.00")), any(ZonedDateTime.class));
        MarketplaceListingSyncRequest request = insertedRequest();
        assertThat(request.getSyncRequestTypeCode()).isEqualTo("LISTING_CREATE");
        assertThat(request.getSyncReasonCode()).isEqualTo("INITIAL_PRICE_APPLIED");
        assertThat(request.getRemoteInventoryId()).isNull();
        assertThat(request.getRemoteVisibilityScopeCode()).isEqualTo("STOCKROOM");
        assertThat(request.getRemoteVisibilityContainerId()).isEqualTo("C");
        assertThat(request.getRemoteIsPubliclyAvailable()).isFalse();
        assertThat(request.getAppliedLocalAt()).isNotNull();
        assertThat(transactions.executions).isOne();
    }

    @Test
    void applyLocalOnlyWritesThePriceAndMarksTheDecisionAppliedWithoutASyncRequest() {
        properties.setMode("APPLY_LOCAL_ONLY");
        MarketplaceListing listing = active(100, "200.00");
        candidates(listing);
        decides(listing, "210.00");
        hasLot(100);

        RepriceResult result = service.runOnce();

        assertThat(result.appliedLocally()).isOne();
        PricingDecision saved = savedDecision();
        assertThat(saved.getDecisionStatusCode()).isEqualTo("APPLIED");
        assertThat(saved.getAppliedAt()).isNotNull();
        verify(marketplaceListingDao).updateUnitPrice(eq(100), eq(new BigDecimal("210.00")), any(ZonedDateTime.class));
        verify(syncRequestDao, never()).insert(any());
        verify(port, never()).processNow(anyLong());
    }

    @Test
    void aListingWithAChangeInFlightIsNotDecidedSoItIsDecidedAgainNextRun() {
        properties.setMode("APPLY");
        MarketplaceListing listing = active(100, "200.00");
        candidates(listing);
        when(syncRequestDao.findByMarketplaceListingId(100)).thenReturn(Set.of(
                MarketplaceListingSyncRequest.builder().marketplaceListingSyncRequestId(1L).syncRequestStatusCode("CLAIMED").build()));

        RepriceResult result = service.runOnce();

        assertThat(result.waitingForSync()).isOne();
        assertThat(result.decisionsWritten()).isZero();
        verify(decider, never()).decide(any());
        verify(pricingDecisionDao, never()).insert(any());
    }

    @Test
    void handsOffAtMostTheImmediateSyncLimitAndLeavesTheRestForTheSyncJob() {
        properties.setMode("APPLY");
        properties.setImmediateSyncLimit(1);
        MarketplaceListing first = active(100, "200.00");
        MarketplaceListing second = active(102, "300.00");
        candidates(first, second);
        decides(first, "210.00");
        decides(second, "310.00");
        hasLot(100);
        hasLot(102);

        RepriceResult result = service.runOnce();

        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.handedOff()).isOne();
        verify(port, times(1)).processNow(anyLong());
    }

    @Test
    void withoutMarketplaceSyncTheRequestStaysPendingForTheSyncJob() {
        properties.setMode("APPLY");
        when(ports.orderedStream()).thenAnswer(invocation -> Stream.empty());
        MarketplaceListing listing = active(100, "200.00");
        candidates(listing);
        decides(listing, "210.00");
        hasLot(100);

        RepriceResult result = service.runOnce();

        assertThat(result.accepted()).isOne();
        assertThat(result.handedOff()).isZero();
    }

    @Test
    void aHandOffThatIsNotConfirmedIsCountedButNotConfirmed() {
        properties.setMode("APPLY");
        when(port.processNow(anyLong())).thenReturn(new MarketplaceSyncPort.HandOff(true, "PENDING"));
        MarketplaceListing listing = active(100, "200.00");
        candidates(listing);
        decides(listing, "210.00");
        hasLot(100);

        RepriceResult result = service.runOnce();

        assertThat(result.handedOff()).isOne();
        assertThat(result.syncConfirmed()).isZero();
    }

    @Test
    void oneListingThatFailsDoesNotStopTheOthers() {
        properties.setMode("APPLY");
        MarketplaceListing broken = active(100, "200.00");
        MarketplaceListing fine = active(102, "300.00");
        candidates(broken, fine);
        when(decider.decide(broken)).thenThrow(new IllegalStateException("snapshot read failed"));
        decides(fine, "310.00");
        hasLot(102);

        RepriceResult result = service.runOnce();

        assertThat(result.errors()).isOne();
        assertThat(result.accepted()).isOne();
        assertThat(result.outcome()).isEqualTo("PARTIAL_SUCCESS");
    }

    @Test
    void aRequestThatAlreadyExistsForTheDecisionRollsTheListingBack() {
        properties.setMode("APPLY");
        MarketplaceListing listing = active(100, "200.00");
        candidates(listing);
        decides(listing, "210.00");
        hasLot(100);
        when(syncRequestDao.findByMarketplaceListingIdAndPricingDecisionIdAndSyncRequestTypeCode(any(), any(), any()))
                .thenReturn(Optional.of(new MarketplaceListingSyncRequest()));

        RepriceResult result = service.runOnce();

        assertThat(result.errors()).isOne();
        verify(syncRequestDao, never()).insert(any());
        verify(port, never()).processNow(anyLong());
    }

    private void candidates(MarketplaceListing... listings) {
        when(marketplaceListingDao.findPricingDecisionCandidatesByListingExternalServiceIdAndListingStatusCodes(
                eq(2), any(), eq(10), eq(true)))
                .thenReturn(new LinkedHashSet<>(List.of(listings)));
    }

    private void decides(MarketplaceListing listing, String finalPrice) {
        when(decider.decide(listing)).thenReturn(PricingDecision.builder()
                .marketplaceListingId(listing.getMarketplaceListingId())
                .decisionStatusCode("PROPOSED")
                .reasonCode("MARKET_POSITION")
                .finalPrice(new BigDecimal(finalPrice))
                .previousPrice(listing.getUnitPrice())
                .currencyCode("USD")
                .confidence(new BigDecimal("0.90"))
                .comparableCount(6)
                .build());
    }

    private void hasLot(int marketplaceListingId) {
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(marketplaceListingId)).thenReturn(Optional.of(
                BricklinkMarketplaceListing.builder().marketplaceListingId(marketplaceListingId).bricklinkInventoryId(12345)
                        .isStockRoom(true).stockRoomId("C").build()));
    }

    private MarketplaceListing active(int id, String price) {
        return MarketplaceListing.builder().marketplaceListingId(id).listingStatusCode("ACTIVE").externalListingId("12345")
                .unitPrice(new BigDecimal(price)).currencyCode("USD").fixedPrice(false).build();
    }

    private PricingDecision savedDecision() {
        ArgumentCaptor<PricingDecision> captor = ArgumentCaptor.forClass(PricingDecision.class);
        verify(pricingDecisionDao).insert(captor.capture());
        return captor.getValue();
    }

    private MarketplaceListingSyncRequest insertedRequest() {
        ArgumentCaptor<MarketplaceListingSyncRequest> captor = ArgumentCaptor.forClass(MarketplaceListingSyncRequest.class);
        verify(syncRequestDao).insert(captor.capture());
        return captor.getValue();
    }
}
