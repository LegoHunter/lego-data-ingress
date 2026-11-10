package io.legohunter.ingress.marketplace.bricklink;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BricklinkMarketplaceSyncPropertiesTest {
    @Test
    void treatsMissingProductionPropertyAsNonProduction() {
        BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();
        properties.setEnvironmentCode("anything-at-all");

        assertThat(properties.isProduction()).isFalse();
        assertThat(properties.nonProdEnvironment()).isTrue();
    }

    @Test
    void treatsProductionTrueAsProductionRegardlessOfEnvironmentCode() {
        BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();
        properties.setEnvironmentCode("sandbox");
        properties.setProduction(true);

        assertThat(properties.nonProdEnvironment()).isFalse();
    }

    @Test
    void keepsExistingEffectiveDefaultsAndNormalization() {
        BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();
        properties.setMode(null);
        properties.setBatchSize(0);
        properties.setRetryBackoff(Duration.ofMillis(-1));
        properties.setEnvironmentCode(" Sandbox ");
        properties.setNonProdStockRoomId(" b ");
        properties.setRemarksMaxLength(0);

        assertThat(properties.effectiveMode()).isEqualTo(BricklinkMarketplaceSyncMode.DRY_RUN);
        assertThat(properties.effectiveBatchSize()).isOne();
        assertThat(properties.effectiveRetryBackoff()).isEqualTo(Duration.ofHours(6));
        assertThat(properties.effectiveEnvironmentCode()).isEqualTo("sandbox");
        assertThat(properties.effectiveNonProdStockRoomId()).isEqualTo("B");
        assertThat(properties.effectiveRemarksMaxLength()).isOne();
    }

    @Test
    void hasNoDefaultNonProdStockroomAndFailsInsteadOfFallingBackToA() {
        BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();

        assertThat(properties.getNonProdStockRoomId()).isNull();
        assertThatThrownBy(properties::effectiveNonProdStockRoomId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-prod-stock-room-id is not set");

        properties.setNonProdStockRoomId("  ");
        assertThatThrownBy(properties::effectiveNonProdStockRoomId)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void forcesLocalMarketplaceSyncToDryRunEvenWhenApplyIsConfigured() {
        BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();
        properties.setEnvironmentCode("local");
        properties.setMode("APPLY");

        assertThat(properties.effectiveMode()).isEqualTo(BricklinkMarketplaceSyncMode.DRY_RUN);
    }
}
