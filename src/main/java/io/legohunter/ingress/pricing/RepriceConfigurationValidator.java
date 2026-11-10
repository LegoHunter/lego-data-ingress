package io.legohunter.ingress.pricing;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start when the Reprice job is enabled together with any of the jobs it replaces: the
 * decision, apply-readiness and apply jobs would write decisions and sync requests for the same
 * listings (HOMELAB-23 design, risk "two code paths"; HOMELAB-65). The older jobs are removed in
 * HOMELAB-66.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lego.pricing.reprice", name = "enabled", havingValue = "true")
public class RepriceConfigurationValidator implements InitializingBean {
    private final BricklinkPricingDecisionProperties decisionProperties;
    private final BricklinkPricingApplyReadinessProperties applyReadinessProperties;
    private final BricklinkPricingApplyProperties applyProperties;

    @Override
    public void afterPropertiesSet() {
        List<String> violations = violations(decisionProperties, applyReadinessProperties, applyProperties);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("lego.pricing.reprice.enabled=true replaces these jobs, so they must be off:\n - "
                    + String.join("\n - ", violations));
        }
    }

    static List<String> violations(
            BricklinkPricingDecisionProperties decision,
            BricklinkPricingApplyReadinessProperties applyReadiness,
            BricklinkPricingApplyProperties apply
    ) {
        List<String> violations = new ArrayList<>();
        if (decision.isEnabled()) {
            violations.add("lego.bricklink.pricing.decision.enabled must be false");
        }
        if (applyReadiness.isEnabled()) {
            violations.add("lego.bricklink.pricing.apply-readiness.enabled must be false");
        }
        if (apply.isEnabled()) {
            violations.add("lego.bricklink.pricing.apply.enabled must be false");
        }
        return violations;
    }
}
