package io.legohunter.ingress.pricing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped SANDBOX and DEV profiles bind to the guard and selection settings Reprice ran with before HOMELAB-66
 * moved them into {@code lego.pricing.reprice}. A key that no longer bound would silently fall back to the much
 * looser defaults in application.yml, and SANDBOX applies real (stockroom) BrickLink changes.
 */
class RepriceConfigurationBindingTest {

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev"})
    void lowerEnvironmentsBindTheStrictGuard(String env) throws Exception {
        Binder binder = binder(env);
        RepriceGuardProperties guard = binder.bind("lego.pricing.reprice.guard", Bindable.of(RepriceGuardProperties.class)).get();

        assertThat(guard.effectiveMinimumPriceDelta()).isEqualByComparingTo("0.01");
        assertThat(guard.isMinimumDeltaPercentEnabled()).isTrue();
        assertThat(guard.effectiveMinimumDeltaPercent()).isEqualByComparingTo("0.02");
        assertThat(guard.effectiveMinimumConfidence()).isEqualByComparingTo("0.75");
        assertThat(guard.effectiveMinimumComparableCount()).isEqualTo(3);
        assertThat(guard.effectiveMaximumAbsoluteDelta()).isEqualByComparingTo("50.00");
        assertThat(guard.effectiveMaximumPercentDelta()).isEqualByComparingTo("0.50");
        assertThat(guard.effectiveBlockedReasonCodes())
                .containsExactlyInAnyOrder("NO_CURRENT_COMPARABLES", "NO_EXACT_COMPARABLES", "NO_CURRENT_SNAPSHOT");
        assertThat(guard.effectiveEligibleReasonCodes()).containsExactlyInAnyOrder(
                "MARKET_POSITION", "SINGLE_COMPARABLE_DISCOUNTED", "TWO_COMPARABLES_WEIGHTED",
                "BELOW_MIN_PRICE_CLAMPED", "ABOVE_MAX_PRICE_CLAMPED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev"})
    void lowerEnvironmentsBindTheSameSelectionAsBefore(String env) throws Exception {
        RepriceProperties reprice = binder(env).bind("lego.pricing.reprice", Bindable.of(RepriceProperties.class)).get();
        RepriceAlgorithmProperties algorithm = binder(env)
                .bind("lego.pricing.reprice.algorithm", Bindable.of(RepriceAlgorithmProperties.class)).get();

        assertThat(reprice.getBricklinkExternalServiceId()).isEqualTo(2);
        assertThat(reprice.effectivePriceableListingStatusCodes()).isEqualTo(Set.of("ACTIVE", "DRAFT"));
        assertThat(reprice.isRequireCurrentSnapshot()).isTrue();
        assertThat(reprice.effectiveBatchSize()).isEqualTo(25);
        assertThat(algorithm.effectiveMaximumAbsoluteChangePerRun()).isEqualByComparingTo("50.00");
        assertThat(algorithm.isOwnSeller("tvattima")).isTrue();
    }

    private static Binder binder(String env) throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = new ArrayList<>();
        // The shipped files: the profile first, so it wins over application.yml.
        sources.addAll(loader.load(env, new FileSystemResource("src/main/resources/application-%s.yml".formatted(env))));
        sources.addAll(loader.load("base", new FileSystemResource("src/main/resources/application.yml")));
        return new Binder(ConfigurationPropertySources.from(sources));
    }
}
