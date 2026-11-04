package io.legohunter.ingress.source.bricklink.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkPricingDecisionPropertiesTest {

    @Test
    void defaultsAreTheBalancedPreset() {
        BricklinkPricingDecisionProperties properties = new BricklinkPricingDecisionProperties();

        assertThat(properties.effectiveAggression()).isEqualTo(BricklinkPricingDecisionProperties.Aggression.BALANCED);
        assertThat(properties.targetPercentile("U", "C")).isEqualByComparingTo("0.35");
        assertThat(properties.targetPercentile("U", "X")).isEqualByComparingTo("0.35");
        assertThat(properties.targetPercentile("N", "S")).isEqualByComparingTo("0.20");
        assertThat(properties.targetPercentile("N", "C")).isEqualByComparingTo("0.20");
        assertThat(properties.effectiveTwoComparablesFraction()).isEqualByComparingTo("0.25");
        assertThat(properties.effectiveMaximumChangePerRun()).isEqualByComparingTo("0.25");
        assertThat(properties.effectiveOutlierMaxRatioToMedian()).isEqualByComparingTo("3.0");
    }

    @Test
    void bindsThePresetAndBracketedPerMarketOverridesFromConfiguration() {
        BricklinkPricingDecisionProperties properties = bind(Map.of(
                "lego.bricklink.pricing.decision.aggression", "patient",
                "lego.bricklink.pricing.decision.target-percentiles.[U-C]", "0.40",
                "lego.bricklink.pricing.decision.maximum-change-per-run", "0",
                "lego.bricklink.pricing.decision.box-instructions-adjustment-enabled", "false"
        ));

        assertThat(properties.effectiveAggression()).isEqualTo(BricklinkPricingDecisionProperties.Aggression.PATIENT);
        assertThat(properties.targetPercentile("U", "C")).isEqualByComparingTo("0.40");
        assertThat(properties.targetPercentile("N", "S")).isEqualByComparingTo("0.30");
        assertThat(properties.effectiveMaximumChangePerRun()).isNull();
        assertThat(properties.isBoxInstructionsAdjustmentEnabled()).isFalse();
    }

    @Test
    void keepsTargetPercentilesBetweenZeroAndOne() {
        BricklinkPricingDecisionProperties properties = new BricklinkPricingDecisionProperties();
        properties.setTargetPercentiles(Map.of("U-C", new BigDecimal("1.5"), "N-S", new BigDecimal("-0.2")));

        assertThat(properties.targetPercentile("U", "C")).isEqualByComparingTo("1");
        assertThat(properties.targetPercentile("N", "S")).isEqualByComparingTo("0");
    }

    @Test
    void theShippedApplicationAndSandboxYamlBindToTheBalancedFormula() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = new ArrayList<>();
        sources.addAll(loader.load("sandbox", new ClassPathResource("application-sandbox.yml")));
        sources.addAll(loader.load("base", new ClassPathResource("application.yml")));
        BricklinkPricingDecisionProperties properties = new Binder(ConfigurationPropertySources.from(sources))
                .bind("lego.bricklink.pricing.decision", Bindable.of(BricklinkPricingDecisionProperties.class))
                .get();

        assertThat(properties.effectiveAggression()).isEqualTo(BricklinkPricingDecisionProperties.Aggression.BALANCED);
        assertThat(properties.targetPercentile("U", "C")).isEqualByComparingTo("0.35");
        assertThat(properties.effectiveMaximumChangePerRun()).isEqualByComparingTo("0.25");
        assertThat(properties.effectiveMaximumAbsoluteChangePerRun()).isEqualByComparingTo("50.00");
        assertThat(properties.isBoxInstructionsAdjustmentEnabled()).isTrue();
    }

    private BricklinkPricingDecisionProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("lego.bricklink.pricing.decision", Bindable.of(BricklinkPricingDecisionProperties.class))
                .get();
    }
}
