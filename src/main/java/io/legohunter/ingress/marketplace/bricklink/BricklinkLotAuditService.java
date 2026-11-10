package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that no lot owned by a lower environment can be seen by buyers or sits where it should not.
 *
 * <p>SANDBOX and DEV create and update real lots in the one BrickLink account. The stockroom and
 * remarks guards that keep those lots hidden are part of the same code being tested in SANDBOX, so a
 * bug there could weaken the very guard meant to catch it. This audit therefore shares none of that
 * code: it reads the whole store itself, finds {@code LEGOHUNTER_ENV=<code>} anywhere in a lot's
 * remarks with its own pattern (so a damaged system block still counts), and holds its own copy of
 * the stockroom rule. {@code BricklinkLotAuditServiceTest} checks that copy against
 * {@code LegoEnvironment}.
 *
 * <p>Read-only: it never changes a lot. Every violation is logged at ERROR and counted in metrics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.lot-audit", name = "enabled", havingValue = "true")
public class BricklinkLotAuditService {
    static final String PROD = "prod";
    static final String PROD_STOCKROOM = "A";
    /** Each lower environment's own stockroom. Kept here on purpose; see the class comment. */
    static final Map<String, String> LOWER_ENV_STOCKROOMS = Map.of("local", "C", "sandbox", "C", "dev", "B");
    private static final Pattern ENV_TAG = Pattern.compile("LEGOHUNTER_ENV\\s*=\\s*([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    private final BricklinkRestClient bricklinkRestClient;

    public BricklinkLotAuditReport audit() {
        long start = System.currentTimeMillis();
        ZonedDateTime checkedAt = ZonedDateTime.now(ZoneOffset.UTC);
        List<Inventory> lots;
        try {
            lots = data(bricklinkRestClient.getInventories(Map.of()));
            if (lots == null) {
                throw new IllegalStateException("BrickLink inventory search returned no data");
            }
        } catch (RuntimeException e) {
            log.error("bricklink.lot_audit.failed message={}", e.getMessage(), e);
            return new BricklinkLotAuditReport(BricklinkLotAuditReport.FAILED, checkedAt, 0, 0, emptyCounts(), List.of(), e.getMessage(), elapsed(start));
        }

        List<BricklinkLotAuditReport.Violation> violations = new ArrayList<>();
        int lowerEnvLots = 0;
        for (Inventory lot : lots) {
            Optional<String> environment = taggedEnvironment(lot.getRemarks());
            if (environment.isEmpty() || PROD.equals(environment.get())) {
                continue;
            }
            lowerEnvLots++;
            violation(lot, environment.get()).ifPresent(violations::add);
        }

        violations.forEach(violation -> log.error(
                "bricklink.lot_audit.violation type={} bricklinkInventoryId={} environmentCode={} isStockRoom={} stockRoomId={} itemNumber={}",
                violation.type(), violation.bricklinkInventoryId(), violation.environmentCode(),
                violation.isStockRoom(), violation.stockRoomId(), violation.itemNumber()));
        Map<BricklinkLotAuditViolationType, Integer> counts = emptyCounts();
        violations.forEach(violation -> counts.merge(violation.type(), 1, Integer::sum));
        BricklinkLotAuditReport report = new BricklinkLotAuditReport(
                violations.isEmpty() ? BricklinkLotAuditReport.CLEAN : BricklinkLotAuditReport.VIOLATIONS_FOUND,
                checkedAt, lots.size(), lowerEnvLots, counts, List.copyOf(violations), null, elapsed(start));
        log.info("bricklink.lot_audit.completed outcome={} lotsChecked={} lowerEnvLots={} violations={}",
                report.outcome(), report.lotsChecked(), report.lowerEnvLots(), counts);
        return report;
    }

    /** The most serious problem with a lower-environment lot, or empty when it is where it belongs. */
    private Optional<BricklinkLotAuditReport.Violation> violation(Inventory lot, String environment) {
        boolean stockroom = Boolean.TRUE.equals(lot.getIs_stock_room());
        String stockroomId = lot.getStock_room_id() == null ? "" : lot.getStock_room_id().trim().toUpperCase(Locale.ROOT);
        BricklinkLotAuditViolationType type = null;
        if (!stockroom) {
            type = BricklinkLotAuditViolationType.LOWER_ENV_LOT_PUBLIC;
        } else if (PROD_STOCKROOM.equals(stockroomId)) {
            type = BricklinkLotAuditViolationType.LOWER_ENV_LOT_IN_PROD_STOCKROOM;
        } else if (!stockroomId.equals(LOWER_ENV_STOCKROOMS.get(environment))) {
            // Also covers an environment code this audit does not know: it has no stockroom of its own.
            type = BricklinkLotAuditViolationType.LOWER_ENV_LOT_IN_WRONG_STOCKROOM;
        }
        if (type == null) {
            return Optional.empty();
        }
        return Optional.of(new BricklinkLotAuditReport.Violation(
                type,
                lot.getInventory_id(),
                environment,
                lot.getIs_stock_room(),
                lot.getStock_room_id(),
                lot.getItem() == null ? null : lot.getItem().getNo()
        ));
    }

    /** The environment named by {@code LEGOHUNTER_ENV=} anywhere in the remarks, lower-cased. */
    static Optional<String> taggedEnvironment(String remarks) {
        if (remarks == null) {
            return Optional.empty();
        }
        Matcher matcher = ENV_TAG.matcher(remarks);
        return matcher.find() ? Optional.of(matcher.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    private static Map<BricklinkLotAuditViolationType, Integer> emptyCounts() {
        Map<BricklinkLotAuditViolationType, Integer> counts = new EnumMap<>(BricklinkLotAuditViolationType.class);
        for (BricklinkLotAuditViolationType type : BricklinkLotAuditViolationType.values()) {
            counts.put(type, 0);
        }
        return counts;
    }

    private static <T> T data(BricklinkResource<T> resource) {
        return resource == null ? null : resource.getData();
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
