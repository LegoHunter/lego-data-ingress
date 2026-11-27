package io.legohunter.ingress.metrics;

import io.legohunter.egress.fulfillment.FulfillmentSyncProperties;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.ingress.marketplace.bricklink.BricklinkMarketplaceSyncMode;
import io.legohunter.ingress.marketplace.bricklink.BricklinkMarketplaceSyncProperties;
import io.legohunter.ingress.orders.BricklinkOrderSyncProperties;
import io.legohunter.ingress.pricing.BricklinkPricingCrawlProperties;
import io.legohunter.ingress.pricing.RepriceMode;
import io.legohunter.ingress.pricing.RepriceProperties;
import io.legohunter.ingress.source.lego.woodentoys.capture.LegoWoodenToysCaptureProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class JobConfigurationMetrics {
    private static final String METRIC_NAME = "lego_data_ingress_scheduled_job_config_info";

    public JobConfigurationMetrics(
            MeterRegistry meterRegistry,
            BricklinkOrderSyncProperties bricklinkOrderSyncProperties,
            ImageHostingSyncProperties imageHostingSyncProperties,
            FulfillmentSyncProperties fulfillmentSyncProperties,
            BricklinkPricingCrawlProperties bricklinkPricingCrawlProperties,
            BricklinkMarketplaceSyncProperties bricklinkMarketplaceSyncProperties,
            RepriceProperties repriceProperties,
            LegoWoodenToysCaptureProperties legoWoodenToysCaptureProperties
    ) {
        register(
                meterRegistry,
                "bricklink_order_sync",
                "BrickLink Order Sync",
                bricklinkOrderSyncProperties.getScheduled().isEnabled(),
                bricklinkOrderSyncProperties.getScheduled().isApply()
        );
        register(
                meterRegistry,
                "image_hosting_sync",
                "Image Hosting Sync",
                imageHostingSyncProperties.getSync().getScheduled().isEnabled(),
                imageHostingSyncProperties.getSync().getScheduled().isApply()
        );
        register(
                meterRegistry,
                "fulfillment_sync",
                "Fulfillment Sync",
                fulfillmentSyncProperties.getSync().getScheduled().isEnabled(),
                fulfillmentSyncProperties.getSync().getScheduled().isApply()
        );
        register(
                meterRegistry,
                "bricklink_pricing_crawl",
                "BrickLink Pricing Crawl",
                bricklinkPricingCrawlProperties.isEnabled() && bricklinkPricingCrawlProperties.getScheduled().isEnabled(),
                false
        );
        register(
                meterRegistry,
                "pricing_reprice",
                "Pricing Reprice",
                repriceProperties.isEnabled() && repriceProperties.getScheduled().isEnabled(),
                repriceProperties.effectiveMode() != RepriceMode.DRY_RUN
        );
        register(
                meterRegistry,
                "bricklink_marketplace_sync",
                "BrickLink Marketplace Sync",
                bricklinkMarketplaceSyncProperties.isEnabled() && bricklinkMarketplaceSyncProperties.getScheduled().isEnabled(),
                bricklinkMarketplaceSyncProperties.effectiveMode() == BricklinkMarketplaceSyncMode.APPLY
        );
        register(
                meterRegistry,
                "lego_wooden_toys_capture",
                "LEGO Wooden Toys Catalog Capture",
                legoWoodenToysCaptureProperties.isEnabled() && legoWoodenToysCaptureProperties.getScheduled().isEnabled(),
                true
        );
    }

    private void register(
            MeterRegistry meterRegistry,
            String scheduledJob,
            String displayName,
            boolean enabled,
            boolean apply
    ) {
        Gauge.builder(METRIC_NAME, this, ignored -> 1.0d)
                .description("Configured scheduled job state for Lego Data Ingress. Labels carry enabled and apply values.")
                .tag("scheduled_job", scheduledJob)
                .tag("display_name", displayName)
                .tag("enabled", Boolean.toString(enabled))
                .tag("apply", Boolean.toString(apply))
                .strongReference(true)
                .register(meterRegistry);
    }
}
