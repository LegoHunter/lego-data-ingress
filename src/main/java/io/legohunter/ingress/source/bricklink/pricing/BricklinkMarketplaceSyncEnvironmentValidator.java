package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.ingress.config.environment.LegoEnvironmentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start the application when the BrickLink marketplace-sync settings could let a lower
 * environment touch the wrong lots in the shared BrickLink account (HOMELAB-20).
 *
 * <p>It checks the stockroom rules in {@link BricklinkStockroomPolicy}, and that marketplace sync
 * agrees with {@code lego.environment.production} about whether this runtime is PROD. If the two
 * flags disagree, marketplace sync would treat PROD as a lower environment (or the reverse) while
 * order and fulfillment jobs did not.
 *
 * <p>The check runs whether or not marketplace sync is enabled, because pricing apply also uses
 * the stockroom setting.
 */
@Component
@RequiredArgsConstructor
public class BricklinkMarketplaceSyncEnvironmentValidator implements InitializingBean {

    private final BricklinkMarketplaceSyncProperties marketplaceSyncProperties;
    private final LegoEnvironmentProperties environmentProperties;

    @Override
    public void afterPropertiesSet() {
        List<String> violations = violations(marketplaceSyncProperties, environmentProperties);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Unsafe BrickLink marketplace-sync configuration:\n - "
                    + String.join("\n - ", violations));
        }
    }

    static List<String> violations(BricklinkMarketplaceSyncProperties marketplaceSync, LegoEnvironmentProperties environment) {
        List<String> violations = new ArrayList<>();
        if (marketplaceSync.isProduction() != environment.isProduction()) {
            violations.add("lego.bricklink.marketplace-sync.production (%s) must equal lego.environment.production (%s)"
                    .formatted(marketplaceSync.isProduction(), environment.isProduction()));
        }
        violations.addAll(BricklinkStockroomPolicy.violations(
                marketplaceSync.isProduction(),
                marketplaceSync.effectiveEnvironmentCode(),
                marketplaceSync.isNonProdRequireStockRoom(),
                marketplaceSync.getNonProdStockRoomId()));
        return violations;
    }
}
