package io.legohunter.ingress.source.bricklink.pricing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkStockroomPolicyTest {

    @ParameterizedTest
    @CsvSource({"sandbox, C", "local, C", "dev, B", "' Sandbox ', C", "DEV, B"})
    void eachLowerEnvironmentHasItsOwnStockroom(String environment, String expected) {
        assertThat(BricklinkStockroomPolicy.expectedStockroom(environment)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({"sandbox, C", "local, C", "dev, B", "sandbox, ' c '"})
    void acceptsTheEnvironmentsOwnStockroom(String environment, String stockroom) {
        assertThat(BricklinkStockroomPolicy.violations(false, environment, true, stockroom)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "local", "some-new-env"})
    void rejectsStockroomAOutsideProd(String environment) {
        assertThat(BricklinkStockroomPolicy.violations(false, environment, true, "A"))
                .singleElement().asString().contains("must not be A outside PROD");
        assertThat(BricklinkStockroomPolicy.violations(false, environment, true, " a "))
                .singleElement().asString().contains("must not be A outside PROD");
    }

    @ParameterizedTest
    @CsvSource({"sandbox, B", "local, B", "dev, C"})
    void rejectsAnotherEnvironmentsStockroom(String environment, String stockroom) {
        assertThat(BricklinkStockroomPolicy.violations(false, environment, true, stockroom))
                .singleElement().asString().contains("for environment '" + environment.trim() + "' must be");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void rejectsAMissingStockroomOutsideProd(String stockroom) {
        assertThat(BricklinkStockroomPolicy.violations(false, "sandbox", true, stockroom))
                .singleElement().asString().contains("must be set outside PROD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"D", "Z", "AB", "1"})
    void rejectsValuesThatAreNotBricklinkStockrooms(String stockroom) {
        assertThat(BricklinkStockroomPolicy.violations(false, "sandbox", true, stockroom))
                .singleElement().asString().contains("is not a BrickLink stockroom");
    }

    @Test
    void rejectsTurningOffTheStockroomRequirementOutsideProd() {
        assertThat(BricklinkStockroomPolicy.violations(false, "sandbox", false, "C"))
                .singleElement().asString().contains("non-prod-require-stock-room must be true");
    }

    @Test
    void anUnknownLowerEnvironmentMayUseBOrCButNeverA() {
        assertThat(BricklinkStockroomPolicy.violations(false, "test", true, "B")).isEmpty();
        assertThat(BricklinkStockroomPolicy.violations(false, "test", true, "C")).isEmpty();
        assertThat(BricklinkStockroomPolicy.violations(false, "test", true, "A")).isNotEmpty();
    }

    @Test
    void prodHasNoNonProdStockroomRule() {
        assertThat(BricklinkStockroomPolicy.violations(true, "prod", true, null)).isEmpty();
        assertThat(BricklinkStockroomPolicy.violations(true, "prod", false, "A")).isEmpty();
    }
}
