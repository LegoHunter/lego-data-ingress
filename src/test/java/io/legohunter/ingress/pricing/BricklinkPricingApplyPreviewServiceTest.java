package io.legohunter.ingress.pricing;

import io.legohunter.data.dao.PricingApplyReadinessDao;
import io.legohunter.data.dto.PricingApplyReadinessReview;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingApplyPreviewServiceTest {
    private final PricingApplyReadinessDao pricingApplyReadinessDao = mock(PricingApplyReadinessDao.class);
    private final BricklinkPricingApplyPreviewService service = new BricklinkPricingApplyPreviewService(pricingApplyReadinessDao);

    @Test
    void buildPreviewNormalizesFiltersAndClampsLimit() {
        PricingApplyReadinessReview review = PricingApplyReadinessReview.builder()
                .readinessStatusCode("READY_TO_APPLY")
                .build();
        when(pricingApplyReadinessDao.findLatestReviews("READY_TO_APPLY", "STALE_DECISION", 500))
                .thenReturn(Set.of(review));

        BricklinkPricingApplyPreviewReport report = service.buildPreview(" ready_to_apply ", " stale_decision ", 999);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.readinessStatusCode()).isEqualTo("READY_TO_APPLY");
        assertThat(report.blockReasonCode()).isEqualTo("STALE_DECISION");
        assertThat(report.summary().returnedCount()).isOne();
        assertThat(report.summary().readyToApplyCount()).isOne();
        assertThat(report.summary().blockedCount()).isZero();
        assertThat(report.summary().readinessStatusCounts()).containsEntry("READY_TO_APPLY", 1);
        assertThat(report.summary().blockReasonCounts()).isEmpty();
        assertThat(report.readinessReviews()).containsExactly(review);
        verify(pricingApplyReadinessDao).findLatestReviews("READY_TO_APPLY", "STALE_DECISION", 500);
    }

    @Test
    void buildPreviewSummarizesBlockedReadinessRows() {
        PricingApplyReadinessReview lowConfidence = PricingApplyReadinessReview.builder()
                .readinessStatusCode("BLOCKED_BELOW_MINIMUM_CONFIDENCE")
                .blockReasonCode("BELOW_MINIMUM_CONFIDENCE")
                .build();
        PricingApplyReadinessReview missingPrice = PricingApplyReadinessReview.builder()
                .readinessStatusCode("BLOCKED_MISSING_CURRENT_PRICE")
                .blockReasonCode("MISSING_CURRENT_PRICE")
                .build();
        when(pricingApplyReadinessDao.findLatestReviews(null, null, 100))
                .thenReturn(Set.of(lowConfidence, missingPrice));

        BricklinkPricingApplyPreviewReport report = service.buildPreview(null, null, 100);

        assertThat(report.summary().returnedCount()).isEqualTo(2);
        assertThat(report.summary().readyToApplyCount()).isZero();
        assertThat(report.summary().blockedCount()).isEqualTo(2);
        assertThat(report.summary().readinessStatusCounts())
                .containsEntry("BLOCKED_BELOW_MINIMUM_CONFIDENCE", 1)
                .containsEntry("BLOCKED_MISSING_CURRENT_PRICE", 1);
        assertThat(report.summary().blockReasonCounts())
                .containsEntry("BELOW_MINIMUM_CONFIDENCE", 1)
                .containsEntry("MISSING_CURRENT_PRICE", 1);
    }

    @Test
    void buildPreviewCountsInitialPriceReadinessAsActionable() {
        PricingApplyReadinessReview initialPrice = PricingApplyReadinessReview.builder()
                .readinessStatusCode("READY_TO_APPLY_INITIAL_PRICE")
                .build();
        PricingApplyReadinessReview blocked = PricingApplyReadinessReview.builder()
                .readinessStatusCode("BLOCKED_MISSING_FINAL_PRICE")
                .blockReasonCode("MISSING_FINAL_PRICE")
                .build();
        when(pricingApplyReadinessDao.findLatestReviews(null, null, 100))
                .thenReturn(Set.of(initialPrice, blocked));

        BricklinkPricingApplyPreviewReport report = service.buildPreview(null, null, 100);

        assertThat(report.summary().returnedCount()).isEqualTo(2);
        assertThat(report.summary().readyToApplyCount()).isOne();
        assertThat(report.summary().blockedCount()).isOne();
        assertThat(report.summary().readinessStatusCounts())
                .containsEntry("READY_TO_APPLY_INITIAL_PRICE", 1)
                .containsEntry("BLOCKED_MISSING_FINAL_PRICE", 1);
    }

    @Test
    void buildDryRunApplySelectionSelectsOnlyLatestReadyRows() {
        PricingApplyReadinessReview review = PricingApplyReadinessReview.builder()
                .readinessStatusCode("READY_TO_APPLY")
                .build();
        when(pricingApplyReadinessDao.findLatestReadyToApplyReviews(1)).thenReturn(Set.of(review));

        BricklinkPricingDryRunApplySelectionReport report = service.buildDryRunApplySelection(0);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.selectedCount()).isOne();
        assertThat(report.selectedReadinessReviews()).containsExactly(review);
        verify(pricingApplyReadinessDao).findLatestReadyToApplyReviews(1);
    }
}
