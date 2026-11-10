package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.data.enums.LegoEnvironment;
import io.legohunter.lego.data.environment.LegoEnvironmentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Compares the whole BrickLink store with the local listings, read-only on both sides.
 *
 * <p>One store read per run. For each ACTIVE local BrickLink listing with a lot id it checks that
 * the lot still exists and that its price, quantity, visibility and system remarks block agree with
 * the listing. It also reports lots tagged for this environment that no local listing owns. Drift is
 * logged and published as metrics; nothing is changed. It is the base for first-sale-wins delisting
 * later: a lot that disappeared has usually been sold.
 *
 * <p>Price drift is not reported for a listing with a PENDING or CLAIMED sync request: the price
 * change is in flight, and the sync worker moves the local price only after BrickLink confirms it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.inventory-reconciliation", name = "enabled", havingValue = "true")
public class BricklinkInventoryReconciliationService {
    private static final String LISTING_STATUS_ACTIVE = "ACTIVE";
    private static final Set<String> OPEN_SYNC_STATUSES = Set.of("PENDING", "CLAIMED");

    private final BricklinkInventoryReconciliationProperties properties;
    private final LegoEnvironmentProperties environment;
    private final BricklinkRestClient bricklinkRestClient;
    private final MarketplaceListingDao marketplaceListingDao;
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao;
    private final MarketplaceListingSyncRequestDao marketplaceListingSyncRequestDao;

    public BricklinkInventoryReconciliationReport reconcile() {
        long start = System.currentTimeMillis();
        String environmentCode = environmentCode();
        ZonedDateTime checkedAt = now();
        List<Inventory> lots;
        try {
            lots = data(bricklinkRestClient.getInventories(Map.of()));
            if (lots == null) {
                throw new IllegalStateException("BrickLink inventory search returned no data");
            }
        } catch (RuntimeException e) {
            log.error("bricklink.inventory_reconciliation.failed environmentCode={} message={}", environmentCode, e.getMessage(), e);
            return new BricklinkInventoryReconciliationReport(
                    BricklinkInventoryReconciliationReport.FAILED, environmentCode, checkedAt,
                    0, 0, 0, 0, 0, emptyCounts(), List.of(), e.getMessage(), elapsed(start));
        }

        Map<Long, Inventory> lotsById = new HashMap<>();
        lots.stream().filter(lot -> lot.getInventory_id() != null).forEach(lot -> lotsById.put(lot.getInventory_id(), lot));
        List<Inventory> ownedLots = lots.stream().filter(lot -> environmentCode.equals(blockEnvironment(lot))).toList();

        List<LocalListing> localListings = localListings();
        List<BricklinkInventoryReconciliationReport.Drift> drifts = new ArrayList<>();
        Set<Long> trackedLotIds = new HashSet<>();
        int matched = 0;
        int inFlight = 0;
        for (LocalListing local : localListings) {
            long lotId = local.bricklink().getBricklinkInventoryId().longValue();
            trackedLotIds.add(lotId);
            Inventory lot = lotsById.get(lotId);
            if (lot == null) {
                drifts.add(drift(BricklinkInventoryDriftType.MISSING_REMOTE_LOT, local, lotId, "lot " + lotId, "not in the store"));
                continue;
            }
            matched++;
            boolean priceChangeInFlight = hasOpenSyncRequest(local.listing().getMarketplaceListingId());
            if (priceChangeInFlight) {
                inFlight++;
            }
            compare(local, lot, lotId, priceChangeInFlight, environmentCode, drifts);
        }
        for (Inventory lot : ownedLots) {
            if (!trackedLotIds.contains(lot.getInventory_id())) {
                drifts.add(new BricklinkInventoryReconciliationReport.Drift(
                        BricklinkInventoryDriftType.UNTRACKED_REMOTE_LOT,
                        parseListingId(lot),
                        lot.getInventory_id(),
                        "a local ACTIVE listing owning the lot",
                        "no local listing; remarks name listing " + parseListingId(lot)
                ));
            }
        }

        drifts.forEach(drift -> log.warn(
                "bricklink.inventory_reconciliation.drift environmentCode={} type={} marketplaceListingId={} bricklinkInventoryId={} expected={} actual={}",
                environmentCode, drift.type(), drift.marketplaceListingId(), drift.bricklinkInventoryId(), drift.expected(), drift.actual()));
        Map<BricklinkInventoryDriftType, Integer> counts = emptyCounts();
        drifts.forEach(drift -> counts.merge(drift.type(), 1, Integer::sum));
        BricklinkInventoryReconciliationReport report = new BricklinkInventoryReconciliationReport(
                drifts.isEmpty() ? BricklinkInventoryReconciliationReport.IN_SYNC : BricklinkInventoryReconciliationReport.DRIFT_FOUND,
                environmentCode, checkedAt, lots.size(), ownedLots.size(), localListings.size(), matched, inFlight,
                counts, List.copyOf(drifts), null, elapsed(start));
        log.info(
                "bricklink.inventory_reconciliation.completed environmentCode={} outcome={} remoteLots={} ownedRemoteLots={} localListings={} matchedListings={} inFlightListings={} drifts={}",
                environmentCode, report.outcome(), report.remoteLots(), report.ownedRemoteLots(), report.localListings(),
                report.matchedListings(), report.inFlightListings(), counts);
        return report;
    }

    private void compare(
            LocalListing local,
            Inventory lot,
            long lotId,
            boolean priceChangeInFlight,
            String environmentCode,
            List<BricklinkInventoryReconciliationReport.Drift> drifts
    ) {
        BigDecimal localPrice = money(local.listing().getUnitPrice());
        BigDecimal remotePrice = lot.getUnit_price() == null ? null : money(BigDecimal.valueOf(lot.getUnit_price()));
        if (!priceChangeInFlight && !Objects.equals(localPrice, remotePrice)) {
            drifts.add(drift(BricklinkInventoryDriftType.PRICE_DRIFT, local, lotId, String.valueOf(localPrice), String.valueOf(remotePrice)));
        }

        Integer expectedQuantity = local.bricklink().getLastRemoteQuantity() == null ? 1 : local.bricklink().getLastRemoteQuantity();
        if (!Objects.equals(expectedQuantity, lot.getQuantity())) {
            drifts.add(drift(BricklinkInventoryDriftType.QUANTITY_DRIFT, local, lotId, String.valueOf(expectedQuantity), String.valueOf(lot.getQuantity())));
        }

        Visibility expected = expectedVisibility(local.bricklink(), environmentCode);
        Visibility actual = new Visibility(Boolean.TRUE.equals(lot.getIs_stock_room()), normalizeStockroom(lot.getStock_room_id(), lot.getIs_stock_room()));
        if (!expected.equals(actual)) {
            drifts.add(drift(BricklinkInventoryDriftType.VISIBILITY_DRIFT, local, lotId, expected.describe(), actual.describe()));
        }

        Optional<BricklinkInventorySystemRemarks.SystemBlock> block = BricklinkInventorySystemRemarks.parse(lot.getRemarks());
        String listingId = String.valueOf(local.listing().getMarketplaceListingId());
        if (block.isEmpty()) {
            drifts.add(drift(BricklinkInventoryDriftType.REMARKS_DRIFT, local, lotId, "system block for listing " + listingId, "no valid system block"));
        } else {
            String blockEnvironment = trim(block.get().value(BricklinkInventorySystemRemarks.ENV_KEY)).toLowerCase();
            String blockListing = trim(block.get().value(BricklinkInventorySystemRemarks.MARKETPLACE_LISTING_ID_KEY));
            if (!environmentCode.equals(blockEnvironment) || !listingId.equals(blockListing)) {
                drifts.add(drift(BricklinkInventoryDriftType.REMARKS_DRIFT, local, lotId,
                        "env=" + environmentCode + " listing=" + listingId,
                        "env=" + blockEnvironment + " listing=" + blockListing));
            }
        }
    }

    /**
     * Outside PROD every lot must be in this environment's stockroom. PROD lots are public unless
     * deliberately hidden, so PROD compares with what the listing recorded.
     */
    private Visibility expectedVisibility(BricklinkMarketplaceListing bricklink, String environmentCode) {
        if (!environment.isProduction()) {
            String stockroom = LegoEnvironment.fromCode(environmentCode)
                    .map(env -> env.stockroom().id())
                    .orElseThrow(() -> new IllegalStateException("Unknown environment code " + environmentCode));
            return new Visibility(true, stockroom);
        }
        return new Visibility(Boolean.TRUE.equals(bricklink.getIsStockRoom()), normalizeStockroom(bricklink.getStockRoomId(), bricklink.getIsStockRoom()));
    }

    private List<LocalListing> localListings() {
        Map<Integer, MarketplaceListing> listings = new HashMap<>();
        marketplaceListingDao.findAll().stream()
                .filter(listing -> Objects.equals(properties.getBricklinkExternalServiceId(), listing.getListingExternalServiceId()))
                .filter(listing -> LISTING_STATUS_ACTIVE.equalsIgnoreCase(trim(listing.getListingStatusCode())))
                .forEach(listing -> listings.put(listing.getMarketplaceListingId(), listing));
        return bricklinkMarketplaceListingDao.findAll().stream()
                .filter(bricklink -> bricklink.getBricklinkInventoryId() != null)
                .filter(bricklink -> listings.containsKey(bricklink.getMarketplaceListingId()))
                .map(bricklink -> new LocalListing(listings.get(bricklink.getMarketplaceListingId()), bricklink))
                .toList();
    }

    private boolean hasOpenSyncRequest(Integer marketplaceListingId) {
        return marketplaceListingSyncRequestDao.findByMarketplaceListingId(marketplaceListingId).stream()
                .map(MarketplaceListingSyncRequest::getSyncRequestStatusCode)
                .map(status -> trim(status).toUpperCase())
                .anyMatch(OPEN_SYNC_STATUSES::contains);
    }

    private String blockEnvironment(Inventory lot) {
        return BricklinkInventorySystemRemarks.parse(lot.getRemarks())
                .map(block -> trim(block.value(BricklinkInventorySystemRemarks.ENV_KEY)).toLowerCase())
                .orElse(null);
    }

    private Integer parseListingId(Inventory lot) {
        return BricklinkInventorySystemRemarks.parse(lot.getRemarks())
                .map(block -> trim(block.value(BricklinkInventorySystemRemarks.MARKETPLACE_LISTING_ID_KEY)))
                .filter(value -> value.matches("\\d+"))
                .map(Integer::valueOf)
                .orElse(null);
    }

    private BricklinkInventoryReconciliationReport.Drift drift(
            BricklinkInventoryDriftType type, LocalListing local, long lotId, String expected, String actual) {
        return new BricklinkInventoryReconciliationReport.Drift(type, local.listing().getMarketplaceListingId(), lotId, expected, actual);
    }

    private static Map<BricklinkInventoryDriftType, Integer> emptyCounts() {
        Map<BricklinkInventoryDriftType, Integer> counts = new EnumMap<>(BricklinkInventoryDriftType.class);
        for (BricklinkInventoryDriftType type : BricklinkInventoryDriftType.values()) {
            counts.put(type, 0);
        }
        return counts;
    }

    private String environmentCode() {
        return environment.getCode() == null || environment.getCode().isBlank() ? "local" : environment.getCode().trim().toLowerCase();
    }

    /** A lot that is not in a stockroom has no meaningful stockroom id. */
    private static String normalizeStockroom(String stockroomId, Boolean isStockRoom) {
        if (!Boolean.TRUE.equals(isStockRoom) || stockroomId == null || stockroomId.isBlank()) {
            return null;
        }
        return stockroomId.trim().toUpperCase();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static <T> T data(BricklinkResource<T> resource) {
        return resource == null ? null : resource.getData();
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private record LocalListing(MarketplaceListing listing, BricklinkMarketplaceListing bricklink) {
    }

    private record Visibility(boolean stockroom, String stockroomId) {
        private String describe() {
            return stockroom ? "stockroom " + stockroomId : "public";
        }
    }
}
