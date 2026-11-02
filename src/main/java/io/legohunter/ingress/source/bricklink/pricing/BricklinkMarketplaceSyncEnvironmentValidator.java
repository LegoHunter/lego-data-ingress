package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.bricklink.BricklinkStockroomPolicy;
import io.legohunter.ingress.config.environment.LegoEnvironmentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start the application when the BrickLink settings could let a lower environment
 * touch the wrong lots in the shared BrickLink account.
 *
 * <p>The stockroom rules themselves are shared with lego-data-service and live in lego-data
 * ({@link BricklinkStockroomPolicy}, {@link io.legohunter.data.enums.LegoEnvironment}): the
 * environment code must be a known environment, must agree with the production flag, and a lower
 * environment must use its own stockroom (SANDBOX/local C, DEV B; A is PROD's own).
 *
 * <p>On top of that, this service has several copies of the same environment facts, and they must
 * agree:
 * <ul>
 *   <li>{@code lego.bricklink.marketplace-sync.production} must equal
 *       {@code lego.environment.production}, or marketplace sync and the order/fulfillment jobs
 *       would disagree about whether this runtime is PROD;</li>
 *   <li>{@code lego.bricklink.pricing.apply.environment-code} must equal the marketplace-sync
 *       environment code, because both are written onto the sync requests;</li>
 *   <li>{@code non-prod-require-stock-room} must stay {@code true} outside PROD.</li>
 * </ul>
 *
 * <p>The check runs whether or not marketplace sync is enabled, because pricing apply also uses
 * these settings.
 */
@Component
@RequiredArgsConstructor
public class BricklinkMarketplaceSyncEnvironmentValidator implements InitializingBean {

    static final String MARKETPLACE_SYNC_PREFIX = "lego.bricklink.marketplace-sync";
    static final String STOCKROOM_SETTING = "non-prod-stock-room-id";

    private final BricklinkMarketplaceSyncProperties marketplaceSyncProperties;
    private final BricklinkPricingApplyProperties pricingApplyProperties;
    private final LegoEnvironmentProperties environmentProperties;

    @Override
    public void afterPropertiesSet() {
        List<String> violations = violations(marketplaceSyncProperties, pricingApplyProperties, environmentProperties);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Unsafe BrickLink marketplace-sync configuration:\n - "
                    + String.join("\n - ", violations));
        }
    }

    static List<String> violations(
            BricklinkMarketplaceSyncProperties marketplaceSync,
            BricklinkPricingApplyProperties pricingApply,
            LegoEnvironmentProperties environment
    ) {
        List<String> violations = new ArrayList<>();
        if (marketplaceSync.isProduction() != environment.isProduction()) {
            violations.add("%s.production (%s) must equal lego.environment.production (%s)"
                    .formatted(MARKETPLACE_SYNC_PREFIX, marketplaceSync.isProduction(), environment.isProduction()));
        }
        if (!pricingApply.effectiveEnvironmentCode().equals(marketplaceSync.effectiveEnvironmentCode())) {
            violations.add("lego.bricklink.pricing.apply.environment-code '%s' must equal %s.environment-code '%s'"
                    .formatted(pricingApply.effectiveEnvironmentCode(), MARKETPLACE_SYNC_PREFIX, marketplaceSync.effectiveEnvironmentCode()));
        }
        if (!marketplaceSync.isProduction() && !marketplaceSync.isNonProdRequireStockRoom()) {
            violations.add("%s.non-prod-require-stock-room must be true outside PROD: lower environments share the real BrickLink account, so every lot they touch must be hidden in a stockroom"
                    .formatted(MARKETPLACE_SYNC_PREFIX));
        }
        violations.addAll(BricklinkStockroomPolicy.violations(
                MARKETPLACE_SYNC_PREFIX,
                STOCKROOM_SETTING,
                marketplaceSync.effectiveEnvironmentCode(),
                marketplaceSync.isProduction(),
                marketplaceSync.getNonProdStockRoomId()));
        return violations;
    }
}
