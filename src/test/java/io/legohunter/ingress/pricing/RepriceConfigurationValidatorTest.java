package io.legohunter.ingress.pricing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepriceConfigurationValidatorTest {
    private final BricklinkPricingDecisionProperties decision = new BricklinkPricingDecisionProperties();
    private final BricklinkPricingApplyReadinessProperties applyReadiness = new BricklinkPricingApplyReadinessProperties();
    private final BricklinkPricingApplyProperties apply = new BricklinkPricingApplyProperties();

    @Test
    void startsWhenTheJobsRepriceReplacesAreOff() {
        new RepriceConfigurationValidator(decision, applyReadiness, apply).afterPropertiesSet();

        assertThat(RepriceConfigurationValidator.violations(decision, applyReadiness, apply)).isEmpty();
    }

    @Test
    void refusesToStartWithAnyOfTheOldJobsOn() {
        decision.setEnabled(true);
        applyReadiness.setEnabled(true);
        apply.setEnabled(true);

        assertThat(RepriceConfigurationValidator.violations(decision, applyReadiness, apply)).containsExactly(
                "lego.bricklink.pricing.decision.enabled must be false",
                "lego.bricklink.pricing.apply-readiness.enabled must be false",
                "lego.bricklink.pricing.apply.enabled must be false");
        assertThatThrownBy(() -> new RepriceConfigurationValidator(decision, applyReadiness, apply).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lego.pricing.reprice.enabled=true");
    }
}
