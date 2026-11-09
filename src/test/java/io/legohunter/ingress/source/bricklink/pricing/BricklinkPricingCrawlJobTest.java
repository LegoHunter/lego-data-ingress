package io.legohunter.ingress.source.bricklink.pricing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingCrawlJobTest {
    @Test
    void runOnceDelegatesToCrawlService() {
        BricklinkPricingCrawlService crawlService = mock(BricklinkPricingCrawlService.class);
        BricklinkPricingCrawlResult expected = new BricklinkPricingCrawlResult(
                "SUCCESS", 1, 0, 0, 1, 1, 0, 2, 0, 0, 50L
        );
        when(crawlService.runOnce()).thenReturn(expected);

        BricklinkPricingMetricsService metricsService = mock(BricklinkPricingMetricsService.class);
        BricklinkPricingDataQualityMetrics dataQualityMetrics = mock(BricklinkPricingDataQualityMetrics.class);
        BricklinkPricingCrawlJob job = new BricklinkPricingCrawlJob(crawlService, metricsService, dataQualityMetrics);
        BricklinkPricingCrawlResult result = job.runOnce();

        assertThat(result).isEqualTo(expected);
        verify(crawlService).runOnce();
        verify(metricsService).recordCrawl(expected);
        verify(dataQualityMetrics).refresh();
    }
}
