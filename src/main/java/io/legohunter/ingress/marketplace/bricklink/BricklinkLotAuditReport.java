package io.legohunter.ingress.marketplace.bricklink;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * One audit of the whole BrickLink store.
 *
 * @param outcome         {@code CLEAN}, {@code VIOLATIONS_FOUND} or {@code FAILED}
 * @param lotsChecked     every lot the store returned, in every stockroom
 * @param lowerEnvLots    lots whose remarks tag them for an environment other than PROD
 * @param violationCounts violations by type; every type is present
 */
public record BricklinkLotAuditReport(
        String outcome,
        ZonedDateTime checkedAt,
        int lotsChecked,
        int lowerEnvLots,
        Map<BricklinkLotAuditViolationType, Integer> violationCounts,
        List<Violation> violations,
        String errorMessage,
        long elapsedMillis
) {
    public static final String CLEAN = "CLEAN";
    public static final String VIOLATIONS_FOUND = "VIOLATIONS_FOUND";
    public static final String FAILED = "FAILED";

    public record Violation(
            BricklinkLotAuditViolationType type,
            Long bricklinkInventoryId,
            String environmentCode,
            Boolean isStockRoom,
            String stockRoomId,
            String itemNumber
    ) {
    }
}
