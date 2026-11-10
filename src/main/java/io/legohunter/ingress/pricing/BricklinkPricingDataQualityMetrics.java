package io.legohunter.ingress.pricing;

import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dto.PricingDataQualitySummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;

/**
 * Prometheus gauges for the Pricing Plane data-quality summary: the counts behind
 * {@code GET /internal/bricklink/pricing/data-quality-report}, for the crawl's priceable listing
 * statuses. The pricing crawl job calls {@link #refresh()} after each run, so the gauges cost one
 * aggregate query per crawl run rather than one per scrape. Every series is registered up front so
 * it reports 0 rather than being absent; a failed refresh leaves the previous values.
 *
 * <ul>
 *   <li>{@code bricklink_pricing_data_quality_gaps_current{gap}}: listings with that gap</li>
 *   <li>{@code bricklink_pricing_data_quality_listings_current{scope}}: {@code checked} and
 *       {@code with_gaps}</li>
 *   <li>{@code bricklink_pricing_data_quality_last_refresh_timestamp_seconds}: when the counts
 *       were last refreshed (epoch seconds; 0 before the first refresh)</li>
 * </ul>
 */
@Component
@Slf4j
public class BricklinkPricingDataQualityMetrics {
    static final String GAPS = "bricklink_pricing_data_quality_gaps_current";
    static final String LISTINGS = "bricklink_pricing_data_quality_listings_current";
    static final String LAST_REFRESH = "bricklink_pricing_data_quality_last_refresh_timestamp_seconds";

    private static final Map<String, ToLongFunction<PricingDataQualitySummary>> GAP_COUNTS = new LinkedHashMap<>();
    private static final Map<String, ToLongFunction<PricingDataQualitySummary>> LISTING_COUNTS = new LinkedHashMap<>();

    static {
        GAP_COUNTS.put("condition", PricingDataQualitySummary::getMissingCondition);
        GAP_COUNTS.put("completeness", PricingDataQualitySummary::getMissingCompleteness);
        GAP_COUNTS.put("catalog_item", PricingDataQualitySummary::getMissingCatalogItem);
        GAP_COUNTS.put("item_number", PricingDataQualitySummary::getMissingItemNumber);
        GAP_COUNTS.put("item_type", PricingDataQualitySummary::getMissingItemType);
        GAP_COUNTS.put("internal_item_id", PricingDataQualitySummary::getMissingInternalItemId);
        GAP_COUNTS.put("box_grade", PricingDataQualitySummary::getMissingBoxGrade);
        GAP_COUNTS.put("instructions_grade", PricingDataQualitySummary::getMissingInstructionsGrade);
        LISTING_COUNTS.put("checked", PricingDataQualitySummary::getListingsChecked);
        LISTING_COUNTS.put("with_gaps", PricingDataQualitySummary::getListingsWithGaps);
    }

    private final MarketplaceListingDao marketplaceListingDao;
    private final BricklinkPricingCrawlProperties crawlProperties;
    private final Clock clock;
    private final Map<String, AtomicLong> gaps = new LinkedHashMap<>();
    private final Map<String, AtomicLong> listings = new LinkedHashMap<>();
    private final AtomicLong lastRefreshEpochSeconds = new AtomicLong();

    @Autowired
    public BricklinkPricingDataQualityMetrics(
            MeterRegistry meterRegistry,
            MarketplaceListingDao marketplaceListingDao,
            BricklinkPricingCrawlProperties crawlProperties
    ) {
        this(meterRegistry, marketplaceListingDao, crawlProperties, Clock.systemUTC());
    }

    BricklinkPricingDataQualityMetrics(
            MeterRegistry meterRegistry,
            MarketplaceListingDao marketplaceListingDao,
            BricklinkPricingCrawlProperties crawlProperties,
            Clock clock
    ) {
        this.marketplaceListingDao = marketplaceListingDao;
        this.crawlProperties = crawlProperties;
        this.clock = clock;
        GAP_COUNTS.keySet().forEach(gap -> gaps.put(gap, register(meterRegistry, GAPS,
                "Priceable BrickLink listings missing data the Pricing Plane needs, by gap.", "gap", gap)));
        LISTING_COUNTS.keySet().forEach(scope -> listings.put(scope, register(meterRegistry, LISTINGS,
                "Priceable BrickLink listings checked for data-quality gaps, and those with at least one gap.", "scope", scope)));
        Gauge.builder(LAST_REFRESH, lastRefreshEpochSeconds, AtomicLong::get)
                .description("Epoch seconds when the Pricing Plane data-quality gauges were last refreshed.")
                .register(meterRegistry);
    }

    /**
     * Re-reads the data-quality summary and updates the gauges. Never throws: a failure is logged
     * and the previous values stay, so a database hiccup cannot fail the crawl job that calls it.
     */
    public void refresh() {
        try {
            PricingDataQualitySummary summary = marketplaceListingDao.summarizePricingDataQualityByListingExternalServiceIdAndListingStatusCodes(
                    crawlProperties.getBricklinkExternalServiceId(),
                    crawlProperties.effectivePriceableListingStatusCodes()
            );
            if (summary == null) {
                summary = new PricingDataQualitySummary();
            }
            for (Map.Entry<String, ToLongFunction<PricingDataQualitySummary>> entry : GAP_COUNTS.entrySet()) {
                gaps.get(entry.getKey()).set(entry.getValue().applyAsLong(summary));
            }
            for (Map.Entry<String, ToLongFunction<PricingDataQualitySummary>> entry : LISTING_COUNTS.entrySet()) {
                listings.get(entry.getKey()).set(entry.getValue().applyAsLong(summary));
            }
            lastRefreshEpochSeconds.set(clock.instant().getEpochSecond());
        } catch (RuntimeException e) {
            log.warn("bricklink.pricing.data_quality.metrics_refresh_failed message={}", e.getMessage());
        }
    }

    private static AtomicLong register(MeterRegistry meterRegistry, String name, String description, String tag, String value) {
        AtomicLong holder = new AtomicLong();
        Gauge.builder(name, holder, AtomicLong::get)
                .description(description)
                .tag(tag, value)
                .register(meterRegistry);
        return holder;
    }
}
