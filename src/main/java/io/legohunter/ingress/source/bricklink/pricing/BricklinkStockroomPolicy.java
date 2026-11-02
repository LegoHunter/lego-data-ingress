package io.legohunter.ingress.source.bricklink.pricing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which BrickLink stockroom each environment may use (HOMELAB-20).
 *
 * <p>All LCMS environments share one BrickLink account. A lower environment may create and update
 * real lots, but only hidden stockroom lots in its own stockroom, so buyers never see them and
 * environments never touch each other's lots:
 *
 * <table>
 *   <caption>Stockroom ownership (owner decision, 2026-10-31)</caption>
 *   <tr><th>Stockroom</th><th>Owner</th></tr>
 *   <tr><td>A</td><td>PROD only, for deliberately hiding a real PROD lot from buyers</td></tr>
 *   <tr><td>B</td><td>DEV</td></tr>
 *   <tr><td>C</td><td>SANDBOX (and {@code local}, which only ever runs DRY_RUN)</td></tr>
 * </table>
 *
 * <p>PROD's own lots are public or deliberately hidden in A; the non-prod stockroom setting does
 * not apply to PROD, so no stockroom rule is checked there.
 */
public final class BricklinkStockroomPolicy {

    /** Reserved for PROD. A lower environment must never create or update a lot in it. */
    public static final String PROD_ONLY_STOCKROOM = "A";

    private static final Set<String> BRICKLINK_STOCKROOMS = Set.of("A", "B", "C");

    private static final Map<String, String> EXPECTED_STOCKROOM_BY_ENVIRONMENT = Map.of(
            "sandbox", "C",
            "local", "C",
            "dev", "B");

    private BricklinkStockroomPolicy() {
    }

    /** The stockroom the given lower environment must use, if it is a known environment. */
    public static Optional<String> expectedStockroom(String environmentCode) {
        return Optional.ofNullable(EXPECTED_STOCKROOM_BY_ENVIRONMENT.get(normalize(environmentCode)));
    }

    /**
     * Every way the given marketplace-sync settings break the stockroom rules, or an empty list.
     *
     * @param production        whether this runtime is PROD
     * @param environmentCode   the marketplace-sync environment code (sandbox, dev, local, ...)
     * @param requireStockRoom  {@code non-prod-require-stock-room}
     * @param stockRoomId       {@code non-prod-stock-room-id}
     */
    public static List<String> violations(boolean production, String environmentCode, boolean requireStockRoom, String stockRoomId) {
        List<String> violations = new ArrayList<>();
        if (production) {
            return violations;
        }
        String environment = normalize(environmentCode);
        String stockroom = normalize(stockRoomId).toUpperCase(Locale.ROOT);
        if (!requireStockRoom) {
            violations.add("non-prod-require-stock-room must be true outside PROD: lower environments share the real BrickLink account, so every lot they touch must be hidden in a stockroom");
        }
        if (stockroom.isEmpty()) {
            violations.add("non-prod-stock-room-id must be set outside PROD (SANDBOX=C, DEV=B)");
            return violations;
        }
        if (!BRICKLINK_STOCKROOMS.contains(stockroom)) {
            violations.add("non-prod-stock-room-id '%s' is not a BrickLink stockroom (A, B or C)".formatted(stockroom));
            return violations;
        }
        if (PROD_ONLY_STOCKROOM.equals(stockroom)) {
            violations.add("non-prod-stock-room-id must not be A outside PROD: stockroom A is reserved for PROD's own hidden lots (environment '%s')".formatted(environment));
            return violations;
        }
        expectedStockroom(environment)
                .filter(expected -> !expected.equals(stockroom))
                .ifPresent(expected -> violations.add(
                        "non-prod-stock-room-id for environment '%s' must be %s, not %s (SANDBOX=C, DEV=B)".formatted(environment, expected, stockroom)));
        return violations;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
