package io.legohunter.ingress.source.bricklink.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.legohunter.ingress.source.bricklink.pricing.BricklinkPricingDecisionProperties.Packaging.BI;
import static io.legohunter.ingress.source.bricklink.pricing.BricklinkPricingDecisionProperties.Packaging.NONE;
import static io.legohunter.ingress.source.bricklink.pricing.BricklinkPricingDecisionProperties.Packaging.ONE;
import static org.assertj.core.api.Assertions.assertThat;

class BricklinkPricingDecisionPropertiesTest {

    @Test
    void defaultsAreTheBalancedPreset() {
        BricklinkPricingDecisionProperties properties = new BricklinkPricingDecisionProperties();

        assertThat(properties.effectiveAggression()).isEqualTo(BricklinkPricingDecisionProperties.Aggression.BALANCED);
        assertThat(properties.targetPercentile("U", "C", BI)).isEqualByComparingTo("0.65");
        assertThat(properties.targetPercentile("U", "C", ONE)).isEqualByComparingTo("0.35");
        assertThat(properties.targetPercentile("U", "X", NONE)).isEqualByComparingTo("0.25");
        assertThat(properties.targetPercentile("N", "S", null)).isEqualByComparingTo("0.20");
        assertThat(properties.targetPercentile("N", "C", null)).isEqualByComparingTo("0.20");
        assertThat(properties.isStepDownEnabled()).isFalse();
        assertThat(properties.effectiveStepDownStartAfterWeeks()).isEqualTo(8);
        assertThat(properties.effectiveStepDownWeeklyPercent()).isEqualByComparingTo("0.01");
        assertThat(properties.effectiveStepDownMaximumTotalPercent()).isEqualByComparingTo("0.20");
        assertThat(properties.effectiveStepDownFloorPercentile()).isEqualByComparingTo("0.10");
        assertThat(properties.effectiveTwoComparablesFraction()).isEqualByComparingTo("0.25");
        assertThat(properties.effectiveMaximumChangePerRun()).isEqualByComparingTo("0.25");
        assertThat(properties.effectiveOutlierMaxRatioToMedian()).isEqualByComparingTo("3.0");
    }

    @Test
    void bindsThePresetAndBracketedPerMarketOverridesFromConfiguration() {
        BricklinkPricingDecisionProperties properties = bind(Map.of(
                "lego.bricklink.pricing.decision.aggression", "patient",
                "lego.bricklink.pricing.decision.target-percentiles.[U-C]", "0.40",
                "lego.bricklink.pricing.decision.target-percentiles.[U-C-BI]", "0.70",
                "lego.bricklink.pricing.decision.step-down.enabled", "true",
                "lego.bricklink.pricing.decision.step-down.weekly-percent", "0.02",
                "lego.bricklink.pricing.decision.maximum-change-per-run", "0",
                "lego.bricklink.pricing.decision.box-instructions-adjustment-enabled", "false"
        ));

        assertThat(properties.effectiveAggression()).isEqualTo(BricklinkPricingDecisionProperties.Aggression.PATIENT);
        assertThat(properties.targetPercentile("U", "C", BI)).isEqualByComparingTo("0.70");
        assertThat(properties.targetPercentile("U", "C", NONE)).isEqualByComparingTo("0.40");
        assertThat(properties.targetPercentile("N", "S", null)).isEqualByComparingTo("0.30");
        assertThat(properties.isStepDownEnabled()).isTrue();
        assertThat(properties.effectiveStepDownWeeklyPercent()).isEqualByComparingTo("0.02");
        assertThat(properties.effectiveMaximumChangePerRun()).isNull();
        assertThat(properties.isBoxInstructionsAdjustmentEnabled()).isFalse();
    }

    @Test
    void keepsTargetPercentilesBetweenZeroAndOne() {
        BricklinkPricingDecisionProperties properties = new BricklinkPricingDecisionProperties();
        properties.setTargetPercentiles(Map.of("U-C", new BigDecimal("1.5"), "N-S", new BigDecimal("-0.2")));

        assertThat(properties.targetPercentile("U", "C", BI)).isEqualByComparingTo("1");
        assertThat(properties.targetPercentile("N", "S", null)).isEqualByComparingTo("0");
    }

    @Test
    void theShippedApplicationAndSandboxYamlBindToTheBalancedFormula() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = new ArrayList<>();
        // Read the shipped files from src/main/resources: the test classpath has its own application.yml.
        sources.addAll(loader.load("sandbox", new FileSystemResource("src/main/resources/application-sandbox.yml")));
        sources.addAll(loader.load("base", new FileSystemResource("src/main/resources/application.yml")));
        BricklinkPricingDecisionProperties properties = new Binder(ConfigurationPropertySources.from(sources))
                .bind("lego.bricklink.pricing.decision", Bindable.of(BricklinkPricingDecisionProperties.class))
                .get();

        assertThat(properties.effectiveAggression()).isEqualTo(BricklinkPricingDecisionProperties.Aggression.BALANCED);
        assertThat(properties.targetPercentile("U", "C", BI)).isEqualByComparingTo("0.65");
        assertThat(properties.isStepDownEnabled()).isFalse();
        assertThat(properties.effectiveMaximumChangePerRun()).isEqualByComparingTo("0.25");
        assertThat(properties.effectiveMaximumAbsoluteChangePerRun()).isEqualByComparingTo("50.00");
        assertThat(properties.isBoxInstructionsAdjustmentEnabled()).isTrue();
        assertThat(properties.isOwnSeller("tvattima")).isTrue();
        assertThat(properties.isOwnSeller("TVattima")).isTrue();
        assertThat(properties.isOwnSeller("someone-else")).isFalse();
    }

    private BricklinkPricingDecisionProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("lego.bricklink.pricing.decision", Bindable.of(BricklinkPricingDecisionProperties.class))
                .get();
    }
}
