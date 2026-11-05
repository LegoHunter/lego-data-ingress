package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dto.PricingDataQualitySummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BricklinkPricingDataQualityMetricsTest {
    private static final Instant NOW = Instant.parse("2026-11-05T02:00:00Z");
    private static final List<String> GAPS = List.of(
            "condition", "completeness", "catalog_item", "item_number",
            "item_type", "internal_item_id", "box_grade", "instructions_grade"
    );

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MarketplaceListingDao dao = mock(MarketplaceListingDao.class);
    private final BricklinkPricingCrawlProperties properties = properties();
    private final BricklinkPricingDataQualityMetrics metrics =
            new BricklinkPricingDataQualityMetrics(registry, dao, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void everySeriesReportsZeroBeforeTheFirstRefresh() {
        GAPS.forEach(gap -> assertThat(gap(gap)).isZero());
        assertThat(listings("checked")).isZero();
        assertThat(listings("with_gaps")).isZero();
        assertThat(lastRefresh()).isZero();
    }

    @Test
    void refreshSetsEveryGaugeFromTheSummary() {
        when(dao.summarizePricingDataQualityByListingExternalServiceIdAndListingStatusCodes(2, Set.of("ACTIVE", "DRAFT")))
                .thenReturn(new PricingDataQualitySummary(40, 6, 1, 2, 3, 4, 5, 6, 7, 8));

        metrics.refresh();

        assertThat(listings("checked")).isEqualTo(40);
        assertThat(listings("with_gaps")).isEqualTo(6);
        for (int index = 0; index < GAPS.size(); index++) {
            assertThat(gap(GAPS.get(index))).isEqualTo(index + 1);
        }
        assertThat(lastRefresh()).isEqualTo(NOW.getEpochSecond());
    }

    @Test
    void aFailedRefreshKeepsThePreviousValuesAndDoesNotThrow() {
        when(dao.summarizePricingDataQualityByListingExternalServiceIdAndListingStatusCodes(2, Set.of("ACTIVE", "DRAFT")))
                .thenReturn(PricingDataQualitySummary.builder().listingsChecked(9).missingBoxGrade(3).build())
                .thenThrow(new IllegalStateException("database down"));

        metrics.refresh();
        metrics.refresh();

        assertThat(listings("checked")).isEqualTo(9);
        assertThat(gap("box_grade")).isEqualTo(3);
        assertThat(lastRefresh()).isEqualTo(NOW.getEpochSecond());
    }

    private double gap(String gap) {
        return registry.get(BricklinkPricingDataQualityMetrics.GAPS).tag("gap", gap).gauge().value();
    }

    private double listings(String scope) {
        return registry.get(BricklinkPricingDataQualityMetrics.LISTINGS).tag("scope", scope).gauge().value();
    }

    private double lastRefresh() {
        return registry.get(BricklinkPricingDataQualityMetrics.LAST_REFRESH).gauge().value();
    }

    private static BricklinkPricingCrawlProperties properties() {
        BricklinkPricingCrawlProperties properties = new BricklinkPricingCrawlProperties();
        properties.setBricklinkExternalServiceId(2);
        return properties;
    }
}
