package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.ingress.config.environment.LegoEnvironmentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BricklinkMarketplaceSyncEnvironmentValidatorTest {

    @Test
    void startsWithSandboxInStockroomC() {
        assertThatCode(() -> validator(lowerEnvironment("sandbox", "C"), environment(false)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void refusesToStartWithSandboxInStockroomA() {
        assertThatThrownBy(() -> validator(lowerEnvironment("sandbox", "A"), environment(false)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HOMELAB-20")
                .hasMessageContaining("must not be A outside PROD");
    }

    @Test
    void refusesToStartWithoutAStockroomOutsideProd() {
        assertThatThrownBy(() -> validator(lowerEnvironment("dev", null), environment(false)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be set outside PROD");
    }

    @Test
    void refusesToStartWhenMarketplaceSyncThinksItIsNotProdButTheRuntimeIsProd() {
        BricklinkMarketplaceSyncProperties marketplaceSync = lowerEnvironment("local", "C");

        assertThatThrownBy(() -> validator(marketplaceSync, environment(true)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marketplace-sync.production (false) must equal lego.environment.production (true)");
    }

    @Test
    void refusesToStartWhenMarketplaceSyncThinksItIsProdButTheRuntimeIsNot() {
        BricklinkMarketplaceSyncProperties marketplaceSync = lowerEnvironment("sandbox", "C");
        marketplaceSync.setProduction(true);

        assertThatThrownBy(() -> validator(marketplaceSync, environment(false)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marketplace-sync.production (true) must equal lego.environment.production (false)");
    }

    @Test
    void prodStartsWithoutANonProdStockroom() {
        BricklinkMarketplaceSyncProperties marketplaceSync = new BricklinkMarketplaceSyncProperties();
        marketplaceSync.setProduction(true);
        marketplaceSync.setEnvironmentCode("prod");

        assertThatCode(() -> validator(marketplaceSync, environment(true)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void reportsEveryProblemAtOnce() {
        BricklinkMarketplaceSyncProperties marketplaceSync = lowerEnvironment("sandbox", "A");
        marketplaceSync.setNonProdRequireStockRoom(false);

        assertThat(BricklinkMarketplaceSyncEnvironmentValidator.violations(marketplaceSync, environment(false)))
                .hasSize(2)
                .anySatisfy(v -> assertThat(v).contains("non-prod-require-stock-room must be true"))
                .anySatisfy(v -> assertThat(v).contains("must not be A outside PROD"));
        assertThat(BricklinkMarketplaceSyncEnvironmentValidator.violations(marketplaceSync, environment(true)))
                .hasSize(3)
                .first().asString().contains("must equal lego.environment.production");
    }

    private static BricklinkMarketplaceSyncEnvironmentValidator validator(
            BricklinkMarketplaceSyncProperties marketplaceSync,
            LegoEnvironmentProperties environment
    ) {
        return new BricklinkMarketplaceSyncEnvironmentValidator(marketplaceSync, environment);
    }

    private static BricklinkMarketplaceSyncProperties lowerEnvironment(String environmentCode, String stockroom) {
        BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();
        properties.setEnvironmentCode(environmentCode);
        properties.setNonProdStockRoomId(stockroom);
        return properties;
    }

    private static LegoEnvironmentProperties environment(boolean production) {
        LegoEnvironmentProperties properties = new LegoEnvironmentProperties();
        properties.setProduction(production);
        return properties;
    }
}
