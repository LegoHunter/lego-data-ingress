package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.ingress.pricing.BricklinkPricingApplyProperties;
import io.legohunter.lego.data.environment.LegoEnvironmentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The service-specific checks. The shared stockroom rules are tested in lego-data
 * ({@code BricklinkStockroomPolicyTest}); here they are only checked to be wired in.
 */
class BricklinkMarketplaceSyncEnvironmentValidatorTest {

    @ParameterizedTest
    @CsvSource({"sandbox, C", "local, C", "dev, B"})
    void startsWithEachLowerEnvironmentInItsOwnStockroom(String environment, String stockroom) {
        assertThatCode(() -> validator(lowerEnvironment(environment, stockroom), applyCode(environment), environment(environment, false)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void refusesToStartWithSandboxInStockroomA() {
        assertThatThrownBy(() -> validator(lowerEnvironment("sandbox", "A"), applyCode("sandbox"), environment("sandbox", false)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe BrickLink marketplace-sync configuration")
                .hasMessageNotContaining("HOMELAB")
                .hasMessageContaining("lego.bricklink.marketplace-sync.non-prod-stock-room-id must not be A for environment 'sandbox'");
    }

    @Test
    void refusesToStartWithAnUnknownEnvironmentCode() {
        assertThatThrownBy(() -> validator(lowerEnvironment("sandbx", "B"), applyCode("sandbx"), environment("sandbx", false)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lego.bricklink.marketplace-sync.environment-code 'sandbx' is not a known environment");
    }

    @Test
    void refusesToStartWithoutAStockroomOutsideProd() {
        assertThatThrownBy(() -> validator(lowerEnvironment("dev", null), applyCode("dev"), environment("dev", false)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-prod-stock-room-id must be set for environment 'dev' (expected B)");
    }

    @Test
    void refusesToStartWhenMarketplaceSyncThinksItIsNotProdButTheRuntimeIsProd() {
        assertThat(BricklinkMarketplaceSyncEnvironmentValidator.violations(lowerEnvironment("local", "C"), applyCode("local"), environment("local", true)))
                .singleElement().asString()
                .contains("marketplace-sync.production (false) must equal lego.environment.production (true)");
    }

    @Test
    void refusesToStartWhenMarketplaceSyncThinksItIsProdButTheRuntimeIsNot() {
        BricklinkMarketplaceSyncProperties marketplaceSync = prod();

        assertThat(BricklinkMarketplaceSyncEnvironmentValidator.violations(marketplaceSync, applyCode("prod"), environment("prod", false)))
                .singleElement().asString()
                .contains("marketplace-sync.production (true) must equal lego.environment.production (false)");
    }

    @Test
    void refusesToStartWhenPricingApplyAndMarketplaceSyncNameDifferentEnvironments() {
        assertThat(BricklinkMarketplaceSyncEnvironmentValidator.violations(lowerEnvironment("sandbox", "C"), applyCode("local"), environment("sandbox", false)))
                .singleElement().asString()
                .contains("lego.bricklink.pricing.apply.environment-code 'local' must equal lego.bricklink.marketplace-sync.environment-code 'sandbox'");
    }

    @Test
    void refusesToStartWhenMarketplaceSyncNamesAnotherEnvironmentThanLegoEnvironment() {
        // lego.environment.code is the one the lego-data starter checked against the database.
        assertThat(BricklinkMarketplaceSyncEnvironmentValidator.violations(lowerEnvironment("sandbox", "C"), applyCode("sandbox"), environment("dev", false)))
                .singleElement().asString()
                .contains("lego.bricklink.marketplace-sync.environment-code 'sandbox' must equal lego.environment.code 'dev'");
    }

    @Test
    void refusesToTurnOffTheStockroomRequirementOutsideProd() {
        BricklinkMarketplaceSyncProperties marketplaceSync = lowerEnvironment("sandbox", "C");
        marketplaceSync.setNonProdRequireStockRoom(false);

        assertThat(BricklinkMarketplaceSyncEnvironmentValidator.violations(marketplaceSync, applyCode("sandbox"), environment("sandbox", false)))
                .singleElement().asString().contains("non-prod-require-stock-room must be true outside PROD");
    }

    @Test
    void prodStartsWithoutANonProdStockroom() {
        assertThatCode(() -> validator(prod(), applyCode("prod"), environment("prod", true)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void reportsEveryProblemAtOnce() {
        BricklinkMarketplaceSyncProperties marketplaceSync = lowerEnvironment("sandbox", "A");
        marketplaceSync.setNonProdRequireStockRoom(false);

        assertThat(BricklinkMarketplaceSyncEnvironmentValidator.violations(marketplaceSync, applyCode("local"), environment("sandbox", true)))
                .hasSize(4)
                .anySatisfy(v -> assertThat(v).contains("must equal lego.environment.production"))
                .anySatisfy(v -> assertThat(v).contains("pricing.apply.environment-code"))
                .anySatisfy(v -> assertThat(v).contains("non-prod-require-stock-room must be true"))
                .anySatisfy(v -> assertThat(v).contains("must not be A"));
    }

    private static BricklinkMarketplaceSyncEnvironmentValidator validator(
            BricklinkMarketplaceSyncProperties marketplaceSync,
            BricklinkPricingApplyProperties pricingApply,
            LegoEnvironmentProperties environment
    ) {
        return new BricklinkMarketplaceSyncEnvironmentValidator(marketplaceSync, pricingApply, environment);
    }

    private static BricklinkMarketplaceSyncProperties lowerEnvironment(String environmentCode, String stockroom) {
        BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();
        properties.setEnvironmentCode(environmentCode);
        properties.setNonProdStockRoomId(stockroom);
        return properties;
    }

    private static BricklinkMarketplaceSyncProperties prod() {
        BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();
        properties.setProduction(true);
        properties.setEnvironmentCode("prod");
        return properties;
    }

    private static BricklinkPricingApplyProperties applyCode(String environmentCode) {
        BricklinkPricingApplyProperties properties = new BricklinkPricingApplyProperties();
        properties.setEnvironmentCode(environmentCode);
        return properties;
    }

    private static LegoEnvironmentProperties environment(String code, boolean production) {
        LegoEnvironmentProperties properties = new LegoEnvironmentProperties();
        properties.setCode(code);
        properties.setProduction(production);
        return properties;
    }
}
