package io.legohunter.ingress.source.bricklink.audit;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import com.bricklink.api.rest.model.v1.Item;
import io.legohunter.data.enums.LegoEnvironment;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BricklinkLotAuditServiceTest {
    private final BricklinkRestClient bricklinkRestClient = mock(BricklinkRestClient.class);
    private final BricklinkLotAuditService service = new BricklinkLotAuditService(bricklinkRestClient);

    @Test
    void aPublicLotCarryingASandboxBlockRaisesTheAlert() {
        Inventory publicSandboxLot = lot(1001, "sandbox", false, null);
        arrangeStore(publicSandboxLot, lot(1002, "sandbox", true, "C"));

        BricklinkLotAuditReport report = service.audit();

        assertThat(report.outcome()).isEqualTo("VIOLATIONS_FOUND");
        assertThat(report.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.type()).isEqualTo(BricklinkLotAuditViolationType.LOWER_ENV_LOT_PUBLIC);
            assertThat(violation.bricklinkInventoryId()).isEqualTo(1001L);
            assertThat(violation.environmentCode()).isEqualTo("sandbox");
            assertThat(violation.itemNumber()).isEqualTo("6365-1");
        });
        assertThat(report.violationCounts().get(BricklinkLotAuditViolationType.LOWER_ENV_LOT_PUBLIC)).isOne();
    }

    @Test
    void aStoreWhoseLowerEnvironmentLotsAreInTheirOwnStockroomsIsClean() {
        arrangeStore(
                lot(1001, "sandbox", true, "C"),
                lot(1002, "dev", true, "B"),
                lot(1003, "prod", false, null),
                lot(1004, "prod", true, "A"),
                untaggedLot(1005, false, null)
        );

        BricklinkLotAuditReport report = service.audit();

        assertThat(report.outcome()).isEqualTo("CLEAN");
        assertThat(report.lotsChecked()).isEqualTo(5);
        assertThat(report.lowerEnvLots()).isEqualTo(2);
        assertThat(report.violations()).isEmpty();
        assertThat(report.violationCounts()).hasSize(BricklinkLotAuditViolationType.values().length)
                .allSatisfy((type, count) -> assertThat(count).isZero());
    }

    @Test
    void aLowerEnvironmentLotInStockroomAIsFlagged() {
        arrangeStore(lot(1001, "dev", true, "A"));

        assertThat(service.audit().violations()).singleElement()
                .extracting(BricklinkLotAuditReport.Violation::type)
                .isEqualTo(BricklinkLotAuditViolationType.LOWER_ENV_LOT_IN_PROD_STOCKROOM);
    }

    @Test
    void aLotInAnotherEnvironmentsStockroomIsFlagged() {
        arrangeStore(lot(1001, "sandbox", true, "B"), lot(1002, "dev", true, "C"));

        BricklinkLotAuditReport report = service.audit();

        assertThat(report.violationCounts().get(BricklinkLotAuditViolationType.LOWER_ENV_LOT_IN_WRONG_STOCKROOM)).isEqualTo(2);
    }

    @Test
    void aDamagedSystemBlockStillCountsAsTaggedForTheEnvironment() {
        Inventory damaged = untaggedLot(1001, false, null);
        damaged.setRemarks("notes LEGOHUNTER_ENV = Sandbox; MARKETPLACE_LISTING_ID=959 [SYSTEM_END]");
        arrangeStore(damaged);

        BricklinkLotAuditReport report = service.audit();

        assertThat(report.lowerEnvLots()).isOne();
        assertThat(report.violations()).singleElement()
                .extracting(BricklinkLotAuditReport.Violation::type)
                .isEqualTo(BricklinkLotAuditViolationType.LOWER_ENV_LOT_PUBLIC);
    }

    @Test
    void anUnknownLowerEnvironmentHasNoStockroomOfItsOwn() {
        arrangeStore(lot(1001, "qa", true, "C"));

        assertThat(service.audit().violations()).singleElement()
                .extracting(BricklinkLotAuditReport.Violation::type)
                .isEqualTo(BricklinkLotAuditViolationType.LOWER_ENV_LOT_IN_WRONG_STOCKROOM);
    }

    @Test
    void aFailedStoreReadIsReportedAsFailedNotClean() {
        when(bricklinkRestClient.getInventories(anyMap())).thenThrow(new IllegalStateException("BrickLink returned 401"));

        BricklinkLotAuditReport report = service.audit();

        assertThat(report.outcome()).isEqualTo("FAILED");
        assertThat(report.errorMessage()).contains("401");
    }

    @Test
    void anEmptyStoreResponseIsReportedAsFailedNotClean() {
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(new BricklinkResource<>());

        assertThat(service.audit().outcome()).isEqualTo("FAILED");
    }

    @Test
    void theAuditsOwnStockroomRuleAgreesWithLegoEnvironment() {
        // The audit keeps its own copy on purpose, so a change to the guard code cannot weaken it.
        // This test makes the two disagree loudly instead of silently.
        for (LegoEnvironment environment : LegoEnvironment.values()) {
            if (environment.isProduction()) {
                assertThat(environment.code()).isEqualTo(BricklinkLotAuditService.PROD);
                assertThat(environment.stockroom().id()).isEqualTo(BricklinkLotAuditService.PROD_STOCKROOM);
            } else {
                assertThat(BricklinkLotAuditService.LOWER_ENV_STOCKROOMS)
                        .containsEntry(environment.code(), environment.stockroom().id());
            }
        }
        assertThat(BricklinkLotAuditService.LOWER_ENV_STOCKROOMS).hasSize(LegoEnvironment.values().length - 1);
    }

    @Test
    void readsTheEnvironmentTagAnywhereInTheRemarks() {
        assertThat(BricklinkLotAuditService.taggedEnvironment("[SYSTEM_BEGIN] LEGOHUNTER_ENV=dev [SYSTEM_END]")).contains("dev");
        assertThat(BricklinkLotAuditService.taggedEnvironment("legohunter_env=SANDBOX")).contains("sandbox");
        assertThat(BricklinkLotAuditService.taggedEnvironment("Human notes only")).isEmpty();
        assertThat(BricklinkLotAuditService.taggedEnvironment(null)).isEmpty();
    }

    private void arrangeStore(Inventory... lots) {
        BricklinkResource<List<Inventory>> resource = new BricklinkResource<>();
        resource.setData(Arrays.asList(lots));
        when(bricklinkRestClient.getInventories(anyMap())).thenReturn(resource);
    }

    private Inventory lot(long id, String environmentCode, boolean stockroom, String stockroomId) {
        Inventory lot = untaggedLot(id, stockroom, stockroomId);
        lot.setRemarks("[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=" + environmentCode
                + "; MARKETPLACE_LISTING_ID=1; ITEM_INVENTORY_UUID=u [SYSTEM_END]");
        return lot;
    }

    private Inventory untaggedLot(long id, boolean stockroom, String stockroomId) {
        Inventory lot = new Inventory();
        lot.setInventory_id(id);
        lot.setIs_stock_room(stockroom);
        lot.setStock_room_id(stockroomId);
        Item item = new Item();
        item.setNo("6365-1");
        lot.setItem(item);
        return lot;
    }
}
