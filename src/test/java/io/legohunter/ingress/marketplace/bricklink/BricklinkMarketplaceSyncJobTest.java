package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.ingress.pricing.BricklinkPricingMetricsService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkMarketplaceSyncJobTest {
    @Test
    void runOnceDelegatesToMarketplaceSyncService() {
        BricklinkMarketplaceSyncService marketplaceSyncService = mock(BricklinkMarketplaceSyncService.class);
        BricklinkMarketplaceSyncResult expected = new BricklinkMarketplaceSyncResult(
                "SUCCESS",
                BricklinkMarketplaceSyncMode.APPLY,
                1,
                1,
                1,
                1,
                0,
                0,
                0,
                75
        );
        when(marketplaceSyncService.runOnce()).thenReturn(expected);

        BricklinkPricingMetricsService metricsService = mock(BricklinkPricingMetricsService.class);
        BricklinkMarketplaceSyncJob job = new BricklinkMarketplaceSyncJob(marketplaceSyncService, metricsService);
        BricklinkMarketplaceSyncResult result = job.runOnce();

        assertThat(result).isEqualTo(expected);
        verify(marketplaceSyncService).runOnce();
        verify(metricsService).recordMarketplaceSync(expected);
    }
}
