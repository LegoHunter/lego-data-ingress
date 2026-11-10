package io.legohunter.ingress.pricing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingApplyJobTest {
    @Test
    void runOnceDelegatesToApplyService() {
        BricklinkPricingApplyService applyService = mock(BricklinkPricingApplyService.class);
        BricklinkPricingApplyResult expected = new BricklinkPricingApplyResult(
                "SUCCESS",
                true,
                BricklinkPricingApplyMode.APPLY_LOCAL_ONLY,
                1,
                1,
                0,
                0,
                0,
                0,
                50
        );
        when(applyService.runOnce()).thenReturn(expected);

        BricklinkPricingMetricsService metricsService = mock(BricklinkPricingMetricsService.class);
        BricklinkPricingApplyJob job = new BricklinkPricingApplyJob(applyService, metricsService);
        BricklinkPricingApplyResult result = job.runOnce();

        assertThat(result).isEqualTo(expected);
        verify(applyService).runOnce();
        verify(metricsService).recordApply(expected);
    }
}
