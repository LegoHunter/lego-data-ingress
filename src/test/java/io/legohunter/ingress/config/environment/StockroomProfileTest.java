package io.legohunter.ingress.config.environment;

import io.legohunter.ingress.source.bricklink.pricing.BricklinkMarketplaceSyncEnvironmentValidator;
import io.legohunter.ingress.source.bricklink.pricing.BricklinkMarketplaceSyncProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Every real Spring profile combination binds to a BrickLink stockroom setup that the startup
 * check accepts, with each lower environment in its own stockroom (HOMELAB-20).
 *
 * <p>The profiles are the ones that actually run: a pod runs {@code <env>,kubernetes} (see
 * {@link KubernetesOverlayProfileTest}), and a developer workstation runs the default
 * {@code sandbox,local}. Later files win, as in Spring Boot. Files are read from
 * {@code src/main/resources} because the test classpath has its own {@code application.yml}.
 */
class StockroomProfileTest {

    private static final String RESOURCES = "src/main/resources/";

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "'sandbox,kubernetes', false, C",
            "'dev,kubernetes',     false, B",
            "'sandbox,local',      false, C",
    })
    void eachLowerEnvironmentUsesItsOwnStockroom(String profiles, boolean production, String stockroom) {
        Properties effective = configuration(profiles);
        BricklinkMarketplaceSyncProperties marketplaceSync = marketplaceSync(effective);

        assertThat(environment(effective).isProduction()).isEqualTo(production);
        assertThat(marketplaceSync.isProduction()).isEqualTo(production);
        assertThat(marketplaceSync.isNonProdRequireStockRoom()).isTrue();
        assertThat(marketplaceSync.effectiveNonProdStockRoomId()).isEqualTo(stockroom);
        assertStartupCheckPasses(effective);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"'prod,kubernetes'"})
    void prodMarketplaceSyncKnowsItIsProd(String profiles) {
        Properties effective = configuration(profiles);

        assertThat(environment(effective).isProduction()).isTrue();
        assertThat(marketplaceSync(effective).isProduction()).isTrue();
        assertStartupCheckPasses(effective);
    }

    private static void assertStartupCheckPasses(Properties effective) {
        assertThatCode(() -> new BricklinkMarketplaceSyncEnvironmentValidator(marketplaceSync(effective), environment(effective)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    private static BricklinkMarketplaceSyncProperties marketplaceSync(Properties effective) {
        return new Binder(new MapConfigurationPropertySource(effective))
                .bind("lego.bricklink.marketplace-sync", Bindable.ofInstance(new BricklinkMarketplaceSyncProperties()))
                .get();
    }

    private static LegoEnvironmentProperties environment(Properties effective) {
        return new Binder(new MapConfigurationPropertySource(effective))
                .bind("lego.environment", Bindable.ofInstance(new LegoEnvironmentProperties()))
                .get();
    }

    private static Properties configuration(String profiles) {
        Properties effective = new Properties();
        effective.putAll(load("application.yml"));
        for (String profile : profiles.split(",")) {
            String file = "application-%s.yml".formatted(profile.trim());
            assertThat(new File(RESOURCES + file)).as(file).exists();
            effective.putAll(load(file));
        }
        return effective;
    }

    private static Properties load(String file) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(RESOURCES + file));
        factory.afterPropertiesSet();
        return factory.getObject();
    }
}
