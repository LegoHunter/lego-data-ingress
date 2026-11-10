package io.legohunter.ingress.metrics;

import io.legohunter.egress.fulfillment.FulfillmentSyncProperties;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.ingress.marketplace.bricklink.BricklinkMarketplaceSyncProperties;
import io.legohunter.ingress.orders.BricklinkOrderSyncProperties;
import io.legohunter.ingress.pricing.BricklinkPricingApplyProperties;
import io.legohunter.ingress.pricing.BricklinkPricingApplyReadinessProperties;
import io.legohunter.ingress.pricing.BricklinkPricingCrawlProperties;
import io.legohunter.ingress.pricing.BricklinkPricingDecisionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobConfigurationMetricsTest {
    private static final String METRIC_NAME = "lego_data_ingress_scheduled_job_config_info";

    @Test
    void registersConfiguredScheduledJobStates() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        BricklinkOrderSyncProperties bricklinkProperties = new BricklinkOrderSyncProperties();
        bricklinkProperties.getScheduled().setEnabled(true);
        bricklinkProperties.getScheduled().setApply(true);
        ImageHostingSyncProperties imageHostingProperties = new ImageHostingSyncProperties();
        imageHostingProperties.getSync().getScheduled().setEnabled(true);
        imageHostingProperties.getSync().getScheduled().setApply(false);
        FulfillmentSyncProperties fulfillmentProperties = new FulfillmentSyncProperties();
        fulfillmentProperties.getSync().getScheduled().setEnabled(false);
        fulfillmentProperties.getSync().getScheduled().setApply(false);
        BricklinkPricingCrawlProperties crawlProperties = new BricklinkPricingCrawlProperties();
        crawlProperties.setEnabled(true);
        crawlProperties.getScheduled().setEnabled(true);
        BricklinkPricingDecisionProperties decisionProperties = new BricklinkPricingDecisionProperties();
        decisionProperties.setEnabled(true);
        decisionProperties.getScheduled().setEnabled(false);
        BricklinkPricingApplyReadinessProperties applyReadinessProperties = new BricklinkPricingApplyReadinessProperties();
        applyReadinessProperties.setEnabled(true);
        applyReadinessProperties.getScheduled().setEnabled(true);
        BricklinkPricingApplyProperties applyProperties = new BricklinkPricingApplyProperties();
        applyProperties.setEnabled(true);
        applyProperties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        applyProperties.getScheduled().setEnabled(true);
        BricklinkMarketplaceSyncProperties marketplaceSyncProperties = new BricklinkMarketplaceSyncProperties();
        marketplaceSyncProperties.setEnabled(true);
        marketplaceSyncProperties.setMode("DRY_RUN");
        marketplaceSyncProperties.getScheduled().setEnabled(true);

        new JobConfigurationMetrics(
                meterRegistry,
                bricklinkProperties,
                imageHostingProperties,
                fulfillmentProperties,
                crawlProperties,
                decisionProperties,
                applyReadinessProperties,
                applyProperties,
                marketplaceSyncProperties
        );

        assertThat(meterRegistry.get(METRIC_NAME)
                .tag("scheduled_job", "bricklink_order_sync")
                .tag("display_name", "BrickLink Order Sync")
                .tag("enabled", "true")
                .tag("apply", "true")
                .gauge()
                .value()).isEqualTo(1.0d);
        assertThat(meterRegistry.get(METRIC_NAME)
                .tag("scheduled_job", "image_hosting_sync")
                .tag("display_name", "Image Hosting Sync")
                .tag("enabled", "true")
                .tag("apply", "false")
                .gauge()
                .value()).isEqualTo(1.0d);
        assertThat(meterRegistry.get(METRIC_NAME)
                .tag("scheduled_job", "fulfillment_sync")
                .tag("display_name", "Fulfillment Sync")
                .tag("enabled", "false")
                .tag("apply", "false")
                .gauge()
                .value()).isEqualTo(1.0d);
        assertThat(meterRegistry.get(METRIC_NAME)
                .tag("scheduled_job", "bricklink_pricing_crawl")
                .tag("display_name", "BrickLink Pricing Crawl")
                .tag("enabled", "true")
                .tag("apply", "false")
                .gauge()
                .value()).isEqualTo(1.0d);
        assertThat(meterRegistry.get(METRIC_NAME)
                .tag("scheduled_job", "bricklink_pricing_decision")
                .tag("display_name", "BrickLink Pricing Decision")
                .tag("enabled", "false")
                .tag("apply", "false")
                .gauge()
                .value()).isEqualTo(1.0d);
        assertThat(meterRegistry.get(METRIC_NAME)
                .tag("scheduled_job", "bricklink_pricing_apply_readiness")
                .tag("display_name", "BrickLink Pricing Apply Readiness")
                .tag("enabled", "true")
                .tag("apply", "false")
                .gauge()
                .value()).isEqualTo(1.0d);
        assertThat(meterRegistry.get(METRIC_NAME)
                .tag("scheduled_job", "bricklink_pricing_apply")
                .tag("display_name", "BrickLink Pricing Apply")
                .tag("enabled", "true")
                .tag("apply", "true")
                .gauge()
                .value()).isEqualTo(1.0d);
        assertThat(meterRegistry.get(METRIC_NAME)
                .tag("scheduled_job", "bricklink_marketplace_sync")
                .tag("display_name", "BrickLink Marketplace Sync")
                .tag("enabled", "true")
                .tag("apply", "false")
                .gauge()
                .value()).isEqualTo(1.0d);
    }
}
