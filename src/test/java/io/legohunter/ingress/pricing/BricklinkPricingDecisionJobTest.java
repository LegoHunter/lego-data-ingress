package io.legohunter.ingress.pricing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingDecisionJobTest {
    @Test
    void runOnceDelegatesToDecisionService() {
        BricklinkPricingDecisionService decisionService = mock(BricklinkPricingDecisionService.class);
        BricklinkPricingDecisionResult expected = new BricklinkPricingDecisionResult(
                "SUCCESS",
                1,
                1,
                1,
                0,
                0,
                50L
        );
        when(decisionService.runOnce()).thenReturn(expected);

        BricklinkPricingMetricsService metricsService = mock(BricklinkPricingMetricsService.class);
        BricklinkPricingDecisionJob job = new BricklinkPricingDecisionJob(decisionService, metricsService);
        BricklinkPricingDecisionResult result = job.runOnce();

        assertThat(result).isEqualTo(expected);
        verify(decisionService).runOnce();
        verify(metricsService).recordDecision(expected);
    }
}
