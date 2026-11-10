package io.legohunter.ingress.marketplace.bricklink;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BricklinkInventorySystemRemarksTest {
    private static final String VALID_BLOCK = "[SYSTEM_BEGIN] "
            + "LEGOHUNTER_MANAGED=true; "
            + "LEGOHUNTER_ENV=sandbox; "
            + "MARKETPLACE_LISTING_ID=100; "
            + "ITEM_INVENTORY_UUID=inventory-uuid "
            + "[SYSTEM_END]";

    @Test
    void constructorIsPrivateUtilityConstructor() throws Exception {
        Constructor<BricklinkInventorySystemRemarks> constructor =
                BricklinkInventorySystemRemarks.class.getDeclaredConstructor();

        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();

        constructor.setAccessible(true);
        assertThatCode(constructor::newInstance).doesNotThrowAnyException();
    }

    @Test
    void parseReturnsEmptyForNullBlankAndMissingMarkerInputs() {
        assertThat(BricklinkInventorySystemRemarks.parse(null)).isEmpty();
        assertThat(BricklinkInventorySystemRemarks.parse("")).isEmpty();
        assertThat(BricklinkInventorySystemRemarks.parse("   \r\n\t  ")).isEmpty();
        assertThat(BricklinkInventorySystemRemarks.parse("human remarks only")).isEmpty();
        assertThat(BricklinkInventorySystemRemarks.parse("[SYSTEM_BEGIN] KEY=value")).isEmpty();
        assertThat(BricklinkInventorySystemRemarks.parse("KEY=value [SYSTEM_END]")).isEmpty();
        assertThat(BricklinkInventorySystemRemarks.parse("[SYSTEM_END] KEY=value [SYSTEM_BEGIN]")).isEmpty();
    }

    @Test
    void parseReturnsValuesAndBlockPositionsForValidBlockEmbeddedInHumanRemarks() {
        String remarks = "Visible human note " + VALID_BLOCK + " trailing note";

        Optional<BricklinkInventorySystemRemarks.SystemBlock> result = BricklinkInventorySystemRemarks.parse(remarks);

        assertThat(result).isPresent();
        BricklinkInventorySystemRemarks.SystemBlock block = result.orElseThrow();
        assertThat(block.beginIndex()).isEqualTo("Visible human note ".length());
        assertThat(block.endIndex()).isEqualTo("Visible human note ".length() + VALID_BLOCK.length());
        assertThat(block.value(BricklinkInventorySystemRemarks.MANAGED_KEY)).isEqualTo("true");
        assertThat(block.value(BricklinkInventorySystemRemarks.ENV_KEY)).isEqualTo("sandbox");
        assertThat(block.value(BricklinkInventorySystemRemarks.MARKETPLACE_LISTING_ID_KEY)).isEqualTo("100");
        assertThat(block.value(BricklinkInventorySystemRemarks.ITEM_INVENTORY_UUID_KEY)).isEqualTo("inventory-uuid");
        assertThat(block.value("UNKNOWN_KEY")).isNull();
    }

    @Test
    void parseNormalizesKeysTrimsWhitespaceAndKeepsValuesAfterFirstEqualsSign() {
        String remarks = "[SYSTEM_BEGIN]  legohunter_managed = TRUE ; "
                + " legohunter_env = Sandbox ; "
                + " marketplace_listing_id = 100 ; "
                + " item_inventory_uuid = inventory-uuid ; "
                + " token = one=two=three ; ; "
                + "[SYSTEM_END]";

        BricklinkInventorySystemRemarks.SystemBlock block =
                BricklinkInventorySystemRemarks.parse(remarks).orElseThrow();

        assertThat(block.value(BricklinkInventorySystemRemarks.MANAGED_KEY)).isEqualTo("TRUE");
        assertThat(block.value(BricklinkInventorySystemRemarks.ENV_KEY)).isEqualTo("Sandbox");
        assertThat(block.value(BricklinkInventorySystemRemarks.MARKETPLACE_LISTING_ID_KEY)).isEqualTo("100");
        assertThat(block.value(BricklinkInventorySystemRemarks.ITEM_INVENTORY_UUID_KEY)).isEqualTo("inventory-uuid");
        assertThat(block.value("TOKEN")).isEqualTo("one=two=three");
    }

    @Test
    void parseUsesLastValueWhenAKeyAppearsMoreThanOnce() {
        String remarks = "[SYSTEM_BEGIN] LEGOHUNTER_ENV=dev; LEGOHUNTER_ENV=sandbox [SYSTEM_END]";

        BricklinkInventorySystemRemarks.SystemBlock block =
                BricklinkInventorySystemRemarks.parse(remarks).orElseThrow();

        assertThat(block.value(BricklinkInventorySystemRemarks.ENV_KEY)).isEqualTo("sandbox");
    }

    @Test
    void parseAllowsEmptySystemBlockAndEmptyValues() {
        BricklinkInventorySystemRemarks.SystemBlock emptyBlock =
                BricklinkInventorySystemRemarks.parse("[SYSTEM_BEGIN] ; ; [SYSTEM_END]").orElseThrow();

        assertThat(emptyBlock.values()).isEmpty();

        BricklinkInventorySystemRemarks.SystemBlock emptyValueBlock =
                BricklinkInventorySystemRemarks.parse("[SYSTEM_BEGIN] KEY= [SYSTEM_END]").orElseThrow();

        assertThat(emptyValueBlock.value("KEY")).isEmpty();
    }

    @Test
    void parseRejectsDuplicateBeginOrEndMarkers() {
        assertThat(BricklinkInventorySystemRemarks.parse(
                "[SYSTEM_BEGIN] KEY=value [SYSTEM_BEGIN] OTHER=value [SYSTEM_END]")).isEmpty();
        assertThat(BricklinkInventorySystemRemarks.parse(
                "[SYSTEM_BEGIN] KEY=value [SYSTEM_END] trailing [SYSTEM_END]")).isEmpty();
    }

    @Test
    void parseRejectsMalformedKeyValuePairs() {
        assertThat(BricklinkInventorySystemRemarks.parse("[SYSTEM_BEGIN] KEY [SYSTEM_END]")).isEmpty();
        assertThat(BricklinkInventorySystemRemarks.parse("[SYSTEM_BEGIN] =value [SYSTEM_END]")).isEmpty();
        assertThat(BricklinkInventorySystemRemarks.parse("[SYSTEM_BEGIN] KEY=value; BROKEN [SYSTEM_END]")).isEmpty();
    }

    @Test
    void systemBlockBuildsCanonicalOrderedBlockAndCleansEnvironment() {
        String block = BricklinkInventorySystemRemarks.systemBlock(" Sandbox ", 100, "inventory-uuid");

        assertThat(block).isEqualTo(VALID_BLOCK);
    }

    @Test
    void systemBlockUsesEmptyValuesForNullInputs() {
        String block = BricklinkInventorySystemRemarks.systemBlock(null, null, null);

        assertThat(block).isEqualTo("[SYSTEM_BEGIN] "
                + "LEGOHUNTER_MANAGED=true; "
                + "LEGOHUNTER_ENV=; "
                + "MARKETPLACE_LISTING_ID=; "
                + "ITEM_INVENTORY_UUID= "
                + "[SYSTEM_END]");
    }

    @Test
    void mergeAddsSystemBlockToNullBlankOrHumanOnlyRemarks() {
        String replacement = BricklinkInventorySystemRemarks.systemBlock("sandbox", 100, "inventory-uuid");

        assertThat(BricklinkInventorySystemRemarks.merge(null, replacement)).isEqualTo(replacement);
        assertThat(BricklinkInventorySystemRemarks.merge("   ", replacement)).isEqualTo(replacement);
        assertThat(BricklinkInventorySystemRemarks.merge("Human only", replacement))
                .isEqualTo("Human only " + replacement);
        assertThat(BricklinkInventorySystemRemarks.merge("  Human only  ", replacement))
                .isEqualTo("Human only " + replacement);
    }

    @Test
    void mergeReplacesExistingValidSystemBlockAndPreservesHumanRemarksAroundIt() {
        String replacement = BricklinkInventorySystemRemarks.systemBlock("dev", 101, "new-uuid");
        String existing = "Before " + VALID_BLOCK + " After";

        String merged = BricklinkInventorySystemRemarks.merge(existing, replacement);

        assertThat(merged).isEqualTo("Before  After " + replacement);
        assertThat(BricklinkInventorySystemRemarks.parse(merged).orElseThrow()
                .value(BricklinkInventorySystemRemarks.ENV_KEY)).isEqualTo("dev");
    }

    @Test
    void mergeReturnsOnlyNewSystemBlockWhenExistingRemarksOnlyContainSystemBlock() {
        String replacement = BricklinkInventorySystemRemarks.systemBlock("dev", 101, "new-uuid");

        assertThat(BricklinkInventorySystemRemarks.merge(VALID_BLOCK, replacement)).isEqualTo(replacement);
    }

    @Test
    void mergePreservesMalformedExistingSystemMarkersAsHumanRemarks() {
        String replacement = BricklinkInventorySystemRemarks.systemBlock("sandbox", 100, "inventory-uuid");
        String malformed = "Human [SYSTEM_BEGIN] KEY_WITHOUT_VALUE [SYSTEM_END]";

        assertThat(BricklinkInventorySystemRemarks.merge(malformed, replacement))
                .isEqualTo(malformed + " " + replacement);
    }

    @Test
    void sha256HashesNullAsEmptyStringAndProducesKnownDeterministicValues() {
        assertThat(BricklinkInventorySystemRemarks.sha256(null))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(BricklinkInventorySystemRemarks.sha256(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(BricklinkInventorySystemRemarks.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(BricklinkInventorySystemRemarks.sha256(VALID_BLOCK))
                .isEqualTo(BricklinkInventorySystemRemarks.sha256(VALID_BLOCK));
        assertThat(BricklinkInventorySystemRemarks.sha256(VALID_BLOCK)).hasSize(64);
    }
}
