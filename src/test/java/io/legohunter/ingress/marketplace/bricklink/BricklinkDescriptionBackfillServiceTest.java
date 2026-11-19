package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkDescriptionBackfillServiceTest {
    private final BricklinkInventoryReconciliationService reconciliation = mock(BricklinkInventoryReconciliationService.class);
    private final MarketplaceListingSyncRequestDao syncRequestDao = mock(MarketplaceListingSyncRequestDao.class);
    private final BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();
    private BricklinkDescriptionBackfillService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties.setEnvironmentCode("sandbox");
        properties.setNonProdStockRoomId("C");
        ObjectProvider<BricklinkInventoryReconciliationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(reconciliation);
        service = new BricklinkDescriptionBackfillService(provider, properties, syncRequestDao);
        when(reconciliation.reconcile()).thenReturn(report(
                drift(BricklinkInventoryDriftType.DESCRIPTION_DRIFT, 956, 569454187L, "Summer Cottage"),
                drift(BricklinkInventoryDriftType.PRICE_DRIFT, 957, 569454185L, "74.55"),
                drift(BricklinkInventoryDriftType.DESCRIPTION_DRIFT, 958, 569454192L, "Metro Park & Service Tower")));
        when(syncRequestDao.insert(any())).thenAnswer(invocation -> {
            MarketplaceListingSyncRequest request = invocation.getArgument(0);
            request.setMarketplaceListingSyncRequestId(700L + request.getMarketplaceListingId() - 956);
            return request;
        });
    }

    @Test
    void aDryRunReportsTheDescriptionDriftsAndQueuesNothing() {
        BricklinkDescriptionBackfillService.Result result = service.backfill(false).orElseThrow();

        assertThat(result.applied()).isFalse();
        assertThat(result.rows()).extracting(BricklinkDescriptionBackfillService.Row::marketplaceListingId).containsExactly(956, 958);
        assertThat(result.rows()).allSatisfy(row -> assertThat(row.syncRequestId()).isNull());
        verify(syncRequestDao, never()).insert(any());
    }

    @Test
    void applyQueuesOneStockroomDescriptionUpdatePerDriftedLotAndIgnoresOtherDrift() {
        BricklinkDescriptionBackfillService.Result result = service.backfill(true).orElseThrow();

        ArgumentCaptor<MarketplaceListingSyncRequest> captor = ArgumentCaptor.forClass(MarketplaceListingSyncRequest.class);
        verify(syncRequestDao, org.mockito.Mockito.times(2)).insert(captor.capture());
        MarketplaceListingSyncRequest first = captor.getAllValues().get(0);
        assertThat(first.getMarketplaceListingId()).isEqualTo(956);
        assertThat(first.getSyncRequestTypeCode()).isEqualTo("DESCRIPTION_UPDATE");
        assertThat(first.getSyncRequestStatusCode()).isEqualTo("PENDING");
        assertThat(first.getSyncReasonCode()).isEqualTo("DESCRIPTION_DRIFT_BACKFILL");
        assertThat(first.getRemoteInventoryId()).isEqualTo("569454187");
        assertThat(first.getRemoteVisibilityContainerId()).isEqualTo("C");
        assertThat(first.getRemoteIsPubliclyAvailable()).isFalse();
        assertThat(first.getEnvironmentCode()).isEqualTo("sandbox");
        assertThat(first.getPricingDecisionId()).isNull();
        assertThat(first.getAttemptCount()).isZero();
        assertThat(first.getNextAttemptAt()).isNotNull();
        assertThat(result.rows()).extracting(BricklinkDescriptionBackfillService.Row::syncRequestId).containsExactly(700L, 702L);
    }

    @Test
    void scheduledDriftIsQueuedExceptWhereTheLastUpdateFailedWithinTheBackoff() {
        // HOMELAB-82: listing 958's last DESCRIPTION_UPDATE was blocked an hour ago, so it is held back; a lot that
        // cannot take its text is not queued again every hour. Too-long drift is never queued.
        when(syncRequestDao.findByMarketplaceListingIdAndSyncRequestTypeCodeAndSyncRequestStatusCodes(
                eq(958), eq("DESCRIPTION_UPDATE"), any())).thenReturn(java.util.Set.of(MarketplaceListingSyncRequest.builder()
                .syncRequestStatusCode("BLOCKED").updatedAt(java.time.ZonedDateTime.now().minusHours(1)).build()));
        when(syncRequestDao.findByMarketplaceListingIdAndSyncRequestTypeCodeAndSyncRequestStatusCodes(
                eq(956), eq("DESCRIPTION_UPDATE"), any())).thenReturn(java.util.Set.of(MarketplaceListingSyncRequest.builder()
                .syncRequestStatusCode("BLOCKED").updatedAt(java.time.ZonedDateTime.now().minusHours(7)).build()));

        int queued = service.queueDrift(report(
                drift(BricklinkInventoryDriftType.DESCRIPTION_DRIFT, 956, 569454187L, "Summer Cottage"),
                drift(BricklinkInventoryDriftType.DESCRIPTION_DRIFT, 958, 569454192L, "Metro Park & Service Tower"),
                drift(BricklinkInventoryDriftType.DESCRIPTION_TOO_LONG, 959, 569454190L, "at most 255 characters")));

        assertThat(queued).isOne();
        ArgumentCaptor<MarketplaceListingSyncRequest> captor = ArgumentCaptor.forClass(MarketplaceListingSyncRequest.class);
        verify(syncRequestDao).insert(captor.capture());
        assertThat(captor.getValue().getMarketplaceListingId()).isEqualTo(956);
        assertThat(captor.getValue().getSyncReasonCode()).isEqualTo("DESCRIPTION_DRIFT");
        assertThat(captor.getValue().getCreatedByJobName()).isEqualTo("BricklinkInventoryReconciliationJob");
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutReconciliationThereIsNothingToBackfill() {
        ObjectProvider<BricklinkInventoryReconciliationService> none = mock(ObjectProvider.class);
        BricklinkDescriptionBackfillService withoutReconciliation =
                new BricklinkDescriptionBackfillService(none, properties, syncRequestDao);

        assertThat(withoutReconciliation.backfill(true)).isEmpty();
        verify(syncRequestDao, never()).insert(any());
    }

    private static BricklinkInventoryReconciliationReport.Drift drift(
            BricklinkInventoryDriftType type, int listingId, long lotId, String expected) {
        return new BricklinkInventoryReconciliationReport.Drift(type, listingId, lotId, expected, "(empty)");
    }

    private static BricklinkInventoryReconciliationReport report(BricklinkInventoryReconciliationReport.Drift... drifts) {
        return new BricklinkInventoryReconciliationReport(
                BricklinkInventoryReconciliationReport.DRIFT_FOUND, "sandbox", null, 5, 5, 5, 5, 0, Map.of(), List.of(drifts), null, 10L);
    }
}
