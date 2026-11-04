package io.legohunter.ingress.source.bricklink.pricing;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.data.enums.LegoEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.marketplace-sync", name = "enabled", havingValue = "true")
public class BricklinkMarketplaceSyncService {
    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_CLAIMED = "CLAIMED";
    static final String STATUS_SUCCEEDED = "SUCCEEDED";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_BLOCKED = "BLOCKED";
    private static final String TYPE_LISTING_CREATE = "LISTING_CREATE";
    private static final String TYPE_PRICE_UPDATE = "PRICE_UPDATE";
    private static final String LISTING_STATUS_ACTIVE = "ACTIVE";

    private final BricklinkMarketplaceSyncProperties properties;
    private final MarketplaceListingSyncRequestDao marketplaceListingSyncRequestDao;
    private final MarketplaceListingDao marketplaceListingDao;
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao;
    private final ItemInventoryDao itemInventoryDao;
    private final BricklinkRestClient bricklinkRestClient;
    private final BricklinkRemoteInventorySafetyService safetyService;
    private final BricklinkListingCreateSafetyService listingCreateSafetyService;
    private final BricklinkListingCreateInventoryMapper listingCreateInventoryMapper;
    private final PricingDecisionDao pricingDecisionDao;
    private final TransactionOperations transactions;

    public BricklinkMarketplaceSyncResult runOnce() {
        long start = System.currentTimeMillis();
        BricklinkMarketplaceSyncMode mode = properties.effectiveMode();
        if (mode == BricklinkMarketplaceSyncMode.APPLY) {
            releaseStaleClaims();
        }
        Set<MarketplaceListingSyncRequest> requests = marketplaceListingSyncRequestDao.findClaimableByStatusCodeAndSyncRequestTypeCodes(
                STATUS_PENDING,
                Set.of(TYPE_PRICE_UPDATE, TYPE_LISTING_CREATE),
                now(),
                properties.effectiveBatchSize()
        );
        if (requests.isEmpty()) {
            return BricklinkMarketplaceSyncResult.noWork(mode, elapsedMillis(start));
        }

        Counters counters = new Counters(requests.size());
        for (MarketplaceListingSyncRequest request : requests) {
            MarketplaceListingSyncRequest effectiveRequest = request;
            try {
                String syncRequestType = normalize(request.getSyncRequestTypeCode());
                if (!Set.of(TYPE_PRICE_UPDATE, TYPE_LISTING_CREATE).contains(syncRequestType)) {
                    counters.blocked++;
                    markBlocked(request, "UNSUPPORTED_SYNC_TYPE", "Unsupported sync request type: " + request.getSyncRequestTypeCode());
                    continue;
                }
                if (mode == BricklinkMarketplaceSyncMode.APPLY) {
                    effectiveRequest = marketplaceListingSyncRequestDao.claim(
                            request.getMarketplaceListingSyncRequestId(),
                            STATUS_PENDING,
                            STATUS_CLAIMED,
                            now()
                    ).orElse(null);
                    if (effectiveRequest == null) {
                        continue;
                    }
                    counters.requestsClaimed++;
                }

                if (TYPE_LISTING_CREATE.equals(syncRequestType)) {
                    processListingCreate(effectiveRequest, mode, counters);
                } else {
                    processPriceUpdate(effectiveRequest, mode, counters);
                }
            } catch (RuntimeException e) {
                counters.failed++;
                if (mode == BricklinkMarketplaceSyncMode.APPLY) {
                    markRetryOrFailed(effectiveRequest, e.getMessage());
                }
                log.warn(
                        "bricklink.marketplace_sync.failed marketplaceListingSyncRequestId={} marketplaceListingId={} message={}",
                        request.getMarketplaceListingSyncRequestId(),
                        request.getMarketplaceListingId(),
                        e.getMessage(),
                        e
                );
            }
        }

        return counters.result(mode, elapsedMillis(start));
    }

    private void processPriceUpdate(
            MarketplaceListingSyncRequest request,
            BricklinkMarketplaceSyncMode mode,
            Counters counters
    ) {
        SyncContext context = context(request, true);
        Inventory remoteInventory = data(bricklinkRestClient.getInventories(context.bricklinkInventoryId().longValue()));
        BricklinkRemoteInventorySafetyResult safety = safetyService.verify(
                properties,
                context.listing(),
                context.bricklinkListing(),
                context.itemInventory(),
                remoteInventory
        );
        if (!safety.allowed()) {
            counters.blocked++;
            if (mode == BricklinkMarketplaceSyncMode.APPLY) {
                markBlocked(request, safety.statusCode(), safety.message());
                updateSafetyMetadata(context.bricklinkListing(), safety, remoteInventory);
            }
            log.warn(
                    "bricklink.marketplace_sync.blocked marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} statusCode={} message={}",
                    request.getMarketplaceListingSyncRequestId(),
                    request.getMarketplaceListingId(),
                    context.bricklinkInventoryId(),
                    safety.statusCode(),
                    safety.message()
            );
            return;
        }

        if (mode == BricklinkMarketplaceSyncMode.DRY_RUN) {
            counters.dryRunVerified++;
            log.info(
                    "bricklink.marketplace_sync.dry_run_verified marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} requestedPrice={} currencyCode={}",
                    request.getMarketplaceListingSyncRequestId(),
                    request.getMarketplaceListingId(),
                    context.bricklinkInventoryId(),
                    price(request.getRequestedUnitPrice()),
                    request.getCurrencyCode()
            );
            return;
        }

        updateRemoteInventory(context, request, safety);
        counters.remoteUpdated++;

        // Remote first: read the lot back, and move the local price only to a price BrickLink holds.
        Inventory confirmedInventory = data(bricklinkRestClient.getInventories(context.bricklinkInventoryId().longValue()));
        ReadBackMismatch mismatch = priceUpdateReadBackMismatch(request, safety, confirmedInventory);
        if (mismatch != null) {
            counters.blocked++;
            transactions.executeWithoutResult(status -> {
                recordReadBackMismatch(context.bricklinkListing(), mismatch, confirmedInventory);
                markBlocked(request, mismatch.statusCode(), mismatch.message());
            });
            log.error(
                    "bricklink.marketplace_sync.read_back_mismatch marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} statusCode={} message={}",
                    request.getMarketplaceListingSyncRequestId(),
                    request.getMarketplaceListingId(),
                    context.bricklinkInventoryId(),
                    mismatch.statusCode(),
                    mismatch.message()
            );
            return;
        }

        transactions.executeWithoutResult(status -> {
            confirmLocalPrice(context, request);
            updateSafetyMetadata(context.bricklinkListing(), safety, confirmedInventory);
            markDecisionApplied(request);
            request.setAppliedLocalAt(now());
            markSucceeded(request, safety);
        });
        counters.remoteVerified++;
        log.info(
                "bricklink.marketplace_sync.price_confirmed marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} previousPrice={} confirmedPrice={}",
                request.getMarketplaceListingSyncRequestId(),
                request.getMarketplaceListingId(),
                context.bricklinkInventoryId(),
                price(request.getPreviousUnitPrice()),
                price(request.getRequestedUnitPrice())
        );
    }

    /**
     * What the read-back after a price update must show: the requested price, this environment's
     * stockroom outside PROD, and the system remarks block that was written. Null when it matches.
     */
    private ReadBackMismatch priceUpdateReadBackMismatch(
            MarketplaceListingSyncRequest request,
            BricklinkRemoteInventorySafetyResult safety,
            Inventory confirmed
    ) {
        if (confirmed == null) {
            return new ReadBackMismatch("REMOTE_INVENTORY_MISSING", "BrickLink returned no lot when read back after the price update");
        }
        BigDecimal requested = money(request.getRequestedUnitPrice());
        BigDecimal actual = confirmed.getUnit_price() == null ? null : money(BigDecimal.valueOf(confirmed.getUnit_price()));
        if (requested == null || !requested.equals(actual)) {
            return new ReadBackMismatch("REMOTE_PRICE_MISMATCH", "BrickLink lot holds price " + actual + " after the update, not the requested " + requested);
        }
        if (properties.nonProdEnvironment()) {
            String stockroom = properties.effectiveNonProdStockRoomId();
            if (!Boolean.TRUE.equals(confirmed.getIs_stock_room()) || !stockroom.equalsIgnoreCase(trim(confirmed.getStock_room_id()))) {
                return new ReadBackMismatch("REMOTE_STOCKROOM_MISMATCH",
                        "BrickLink lot is not in stockroom " + stockroom + " after the update (is_stock_room="
                                + confirmed.getIs_stock_room() + ", stock_room_id=" + confirmed.getStock_room_id() + ")");
            }
        }
        if (safety.desiredRemarks() != null && !safety.desiredRemarks().trim().equals(trim(confirmed.getRemarks()))) {
            return new ReadBackMismatch("REMOTE_REMARKS_MISMATCH", "BrickLink lot remarks differ from the system remarks block written by the update");
        }
        return null;
    }

    /**
     * Records the mismatch on the BrickLink listing without touching the local price or the recorded
     * remarks hash: what BrickLink holds is unknown, so the reconciliation job and a person decide.
     */
    private void recordReadBackMismatch(BricklinkMarketplaceListing bricklinkListing, ReadBackMismatch mismatch, Inventory confirmed) {
        bricklinkListing.setLastRemoteVerifiedAt(now());
        bricklinkListing.setLastRemoteSafetyStatusCode(mismatch.statusCode());
        bricklinkListing.setLastRemoteSafetyMessage(mismatch.message());
        if (confirmed != null) {
            bricklinkListing.setIsStockRoom(confirmed.getIs_stock_room());
            bricklinkListing.setStockRoomId(confirmed.getStock_room_id());
        }
        bricklinkMarketplaceListingDao.update(bricklinkListing);
    }

    private void confirmLocalPrice(SyncContext context, MarketplaceListingSyncRequest request) {
        MarketplaceListing listing = context.listing();
        listing.setUnitPrice(money(request.getRequestedUnitPrice()));
        listing.setLastSynchronizedAt(now());
        marketplaceListingDao.update(listing);
    }

    /**
     * ACCEPTED to APPLIED once BrickLink holds the decision's price. Requests made outside the pricing
     * plane carry no decision. A decision that is not ACCEPTED (for example one applied by the older
     * local-first code) is left as it is.
     */
    private void markDecisionApplied(MarketplaceListingSyncRequest request) {
        if (request.getPricingDecisionId() == null) {
            return;
        }
        if (pricingDecisionDao.transitionStatus(
                request.getPricingDecisionId(),
                BricklinkPricingApplyService.STATUS_ACCEPTED,
                BricklinkPricingApplyService.STATUS_APPLIED,
                now()
        ).isEmpty()) {
            log.warn(
                    "bricklink.marketplace_sync.decision_not_accepted marketplaceListingSyncRequestId={} pricingDecisionId={} message=pricing decision was not ACCEPTED, so it was not marked APPLIED",
                    request.getMarketplaceListingSyncRequestId(),
                    request.getPricingDecisionId()
            );
        }
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private record ReadBackMismatch(String statusCode, String message) {
    }

    /**
     * Creates the BrickLink lot for a local draft, at most once.
     *
     * <p>BrickLink has no idempotency key, so a retried create would make a second lot of the same
     * unique item. Three things prevent that:
     * <ol>
     *   <li>the new lot's id is saved on the request and the BrickLink listing as soon as
     *       {@code createInventory} returns, before anything else can fail;</li>
     *   <li>a request that already has a saved id resumes at the read-back instead of creating;</li>
     *   <li>before creating, the store is searched for a lot whose system remarks block names this
     *       environment, listing and inventory item. That finds a lot whose create response was lost
     *       (timeout, connection reset, pod killed mid-call), so it is taken over instead of
     *       duplicated. If the search fails, nothing is created.</li>
     * </ol>
     */
    private void processListingCreate(
            MarketplaceListingSyncRequest request,
            BricklinkMarketplaceSyncMode mode,
            Counters counters
    ) {
        SyncContext context = context(request, false);
        Long savedRemoteInventoryId = savedRemoteInventoryId(request);
        BricklinkListingCreateSafetyResult safety = listingCreateSafetyService.verify(
                properties,
                context.listing(),
                context.bricklinkListing(),
                context.itemInventory(),
                request,
                savedRemoteInventoryId != null
        );
        if (!safety.allowed()) {
            counters.blocked++;
            if (mode == BricklinkMarketplaceSyncMode.APPLY) {
                markBlocked(request, safety.statusCode(), safety.message());
                updateCreateSafetyMetadata(context.bricklinkListing(), safety, null);
            }
            log.warn(
                    "bricklink.marketplace_sync.listing_create_blocked marketplaceListingSyncRequestId={} marketplaceListingId={} statusCode={} message={}",
                    request.getMarketplaceListingSyncRequestId(),
                    request.getMarketplaceListingId(),
                    safety.statusCode(),
                    safety.message()
            );
            return;
        }

        Inventory createPayload = listingCreateInventoryMapper.map(
                context.listing(),
                context.bricklinkListing(),
                context.itemInventory(),
                request,
                safety
        );
        if (mode == BricklinkMarketplaceSyncMode.DRY_RUN) {
            counters.dryRunVerified++;
            log.info(
                    "bricklink.marketplace_sync.listing_create_dry_run marketplaceListingSyncRequestId={} marketplaceListingId={} itemNumber={} itemType={} requestedPrice={} stockRoom={} stockRoomId={} environmentCode={} savedBricklinkInventoryId={}",
                    request.getMarketplaceListingSyncRequestId(),
                    request.getMarketplaceListingId(),
                    createPayload.getItem().getNo(),
                    createPayload.getItem().getType(),
                    price(request.getRequestedUnitPrice()),
                    createPayload.getIs_stock_room(),
                    createPayload.getStock_room_id(),
                    properties.effectiveEnvironmentCode(),
                    savedRemoteInventoryId
            );
            return;
        }

        Long remoteInventoryId = savedRemoteInventoryId;
        if (remoteInventoryId != null) {
            log.info(
                    "bricklink.marketplace_sync.listing_create_resumed marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={}",
                    request.getMarketplaceListingSyncRequestId(),
                    request.getMarketplaceListingId(),
                    remoteInventoryId
            );
        } else {
            List<Long> ownedLots = ownedLotIds(context);
            if (ownedLots.size() > 1) {
                counters.blocked++;
                markBlocked(request, "DUPLICATE_REMOTE_INVENTORY",
                        "BrickLink already has " + ownedLots.size() + " lots owned by this listing " + ownedLots
                                + "; resolve them by hand before creating another");
                log.warn(
                        "bricklink.marketplace_sync.listing_create_duplicates_found marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryIds={}",
                        request.getMarketplaceListingSyncRequestId(),
                        request.getMarketplaceListingId(),
                        ownedLots
                );
                return;
            }
            if (ownedLots.size() == 1) {
                remoteInventoryId = ownedLots.getFirst();
                log.warn(
                        "bricklink.marketplace_sync.listing_create_adopted marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} message=an earlier attempt created this lot but its id was not saved",
                        request.getMarketplaceListingSyncRequestId(),
                        request.getMarketplaceListingId(),
                        remoteInventoryId
                );
            } else {
                Inventory createdInventory = data(bricklinkRestClient.createInventory(createPayload));
                remoteInventoryId = createdInventory == null ? null : createdInventory.getInventory_id();
                if (remoteInventoryId == null) {
                    throw new IllegalStateException("BrickLink createInventory response did not include inventory_id");
                }
            }
            saveRemoteInventoryId(context, request, remoteInventoryId);
        }

        context.bricklinkListing().setBricklinkInventoryId(remoteInventoryId.intValue());
        Inventory verifiedRemoteInventory = data(bricklinkRestClient.getInventories(remoteInventoryId));
        BricklinkRemoteInventorySafetyResult remoteSafety = createdQuantityCheck(
                safetyService.verify(
                        properties,
                        context.listing(),
                        context.bricklinkListing(),
                        context.itemInventory(),
                        verifiedRemoteInventory
                ),
                createPayload,
                verifiedRemoteInventory
        );
        if (!remoteSafety.allowed()) {
            updateCreatedRemoteIdentity(context, request, verifiedRemoteInventory, remoteInventoryId);
            updateSafetyMetadata(context.bricklinkListing(), remoteSafety, verifiedRemoteInventory);
            String hidden = hideBlockedLot(context, request, remoteInventoryId, verifiedRemoteInventory);
            markBlocked(request, remoteSafety.statusCode(), remoteSafety.message() + hidden);
            counters.blocked++;
            log.warn(
                    "bricklink.marketplace_sync.listing_create_remote_verification_blocked marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} statusCode={} message={}",
                    request.getMarketplaceListingSyncRequestId(),
                    request.getMarketplaceListingId(),
                    remoteInventoryId,
                    remoteSafety.statusCode(),
                    remoteSafety.message()
            );
            return;
        }
        Long createdInventoryId = remoteInventoryId;
        transactions.executeWithoutResult(status -> {
            updateCreateSuccessLocalState(context, request, safety, verifiedRemoteInventory, createdInventoryId);
            markDecisionApplied(request);
            request.setAppliedLocalAt(request.getAppliedLocalAt() == null ? now() : request.getAppliedLocalAt());
            markSucceeded(request, remoteSafety);
        });
        counters.remoteVerified++;
        counters.remoteUpdated++;
        log.info(
                "bricklink.marketplace_sync.listing_created marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} stockRoom={} stockRoomId={} requestedPrice={}",
                request.getMarketplaceListingSyncRequestId(),
                request.getMarketplaceListingId(),
                remoteInventoryId,
                safety.stockRoom(),
                safety.stockRoomId(),
                price(request.getRequestedUnitPrice())
        );
    }

    /**
     * A created (or adopted) lot must hold exactly the quantity this listing put up. BrickLink's create
     * call may consolidate a new lot into a matching existing one (its changelog says so without
     * detail), which would leave one lot with a larger quantity that passes every other check. Only
     * the listing-create path checks this: lots that predate it may legitimately hold other
     * quantities.
     */
    private BricklinkRemoteInventorySafetyResult createdQuantityCheck(
            BricklinkRemoteInventorySafetyResult remoteSafety,
            Inventory createPayload,
            Inventory verifiedRemoteInventory
    ) {
        if (!remoteSafety.allowed() || verifiedRemoteInventory == null) {
            return remoteSafety;
        }
        Integer expected = createPayload.getQuantity();
        Integer actual = verifiedRemoteInventory.getQuantity();
        if (Objects.equals(expected, actual)) {
            return remoteSafety;
        }
        return BricklinkRemoteInventorySafetyResult.blocked(
                "REMOTE_QUANTITY_MISMATCH",
                "BrickLink lot holds quantity " + actual + " but this listing put up " + expected
        );
    }

    private void updateCreatedRemoteIdentity(
            SyncContext context,
            MarketplaceListingSyncRequest request,
            Inventory verifiedRemoteInventory,
            Long remoteInventoryId
    ) {
        MarketplaceListing listing = context.listing();
        listing.setExternalListingId(remoteInventoryId.toString());
        listing.setLastSynchronizedAt(now());
        marketplaceListingDao.update(listing);

        context.bricklinkListing().setBricklinkInventoryId(remoteInventoryId.intValue());
        if (verifiedRemoteInventory != null) {
            context.bricklinkListing().setBricklinkDateCreated(zonedDateTime(verifiedRemoteInventory.getDate_created()));
            context.bricklinkListing().setLastRemoteQuantity(verifiedRemoteInventory.getQuantity());
        }
        request.setRemoteInventoryId(remoteInventoryId.toString());
    }

    /**
     * The lot an earlier attempt of this request already created or found, if it saved the id. Only
     * the request's own {@code remote_inventory_id} counts: a listing that already had a lot before
     * this request is still refused by the create safety check, so this never takes over a lot it
     * did not create.
     */
    private Long savedRemoteInventoryId(MarketplaceListingSyncRequest request) {
        String saved = request.getRemoteInventoryId();
        if (saved == null || saved.isBlank()) {
            return null;
        }
        return Long.valueOf(saved.trim());
    }

    /**
     * Saves the new lot's id before anything else can fail. The request is written first: if the
     * BrickLink listing update then fails, the retry still finds the id on the request and resumes.
     */
    private void saveRemoteInventoryId(SyncContext context, MarketplaceListingSyncRequest request, Long remoteInventoryId) {
        request.setRemoteInventoryId(remoteInventoryId.toString());
        context.bricklinkListing().setBricklinkInventoryId(remoteInventoryId.intValue());
        marketplaceListingSyncRequestDao.update(request);
        bricklinkMarketplaceListingDao.update(context.bricklinkListing());
    }

    /**
     * The ids of every lot in the store whose system remarks block names this environment, listing
     * and inventory item: lots an earlier attempt created without saving the id. A failed search
     * throws, so nothing is created while an existing lot cannot be ruled out.
     */
    private List<Long> ownedLotIds(SyncContext context) {
        List<Inventory> lots = data(bricklinkRestClient.getInventories(Map.of()));
        if (lots == null) {
            throw new IllegalStateException("BrickLink inventory search returned no data, so an existing lot cannot be ruled out");
        }
        String environmentCode = properties.effectiveEnvironmentCode();
        String marketplaceListingId = String.valueOf(context.listing().getMarketplaceListingId());
        String itemInventoryUuid = context.itemInventory().getUuid() == null ? "" : context.itemInventory().getUuid();
        return lots.stream()
                .filter(lot -> BricklinkInventorySystemRemarks.parse(lot.getRemarks())
                        .map(block -> environmentCode.equalsIgnoreCase(trim(block.value(BricklinkInventorySystemRemarks.ENV_KEY)))
                                && marketplaceListingId.equals(trim(block.value(BricklinkInventorySystemRemarks.MARKETPLACE_LISTING_ID_KEY)))
                                && itemInventoryUuid.equals(trim(block.value(BricklinkInventorySystemRemarks.ITEM_INVENTORY_UUID_KEY))))
                        .orElse(false))
                .map(Inventory::getInventory_id)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
    }

    /**
     * Hides a lot this request just created (or took over) that failed the post-create check, so a
     * lot that failed our own checks is never left for sale. PROD hides it in stockroom A, its
     * stockroom for deliberately hidden lots; a lower environment uses its own stockroom. Returns a
     * note for the request's error message.
     */
    private String hideBlockedLot(SyncContext context, MarketplaceListingSyncRequest request, Long remoteInventoryId, Inventory remoteInventory) {
        String stockroom = properties.isProduction()
                ? LegoEnvironment.PROD.stockroom().id()
                : properties.effectiveNonProdStockRoomId();
        if (remoteInventory != null && Boolean.TRUE.equals(remoteInventory.getIs_stock_room())
                && stockroom.equalsIgnoreCase(trim(remoteInventory.getStock_room_id()))) {
            return "; lot " + remoteInventoryId + " is already hidden in stockroom " + stockroom;
        }
        try {
            Inventory hide = new Inventory();
            hide.setIs_stock_room(true);
            hide.setStock_room_id(stockroom);
            bricklinkRestClient.updateInventory(remoteInventoryId, hide);
            context.bricklinkListing().setIsStockRoom(true);
            context.bricklinkListing().setStockRoomId(stockroom);
            bricklinkMarketplaceListingDao.update(context.bricklinkListing());
            log.warn(
                    "bricklink.marketplace_sync.listing_create_blocked_lot_hidden marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} stockRoomId={}",
                    request.getMarketplaceListingSyncRequestId(),
                    request.getMarketplaceListingId(),
                    remoteInventoryId,
                    stockroom
            );
            return "; lot " + remoteInventoryId + " hidden in stockroom " + stockroom;
        } catch (RuntimeException e) {
            log.error(
                    "bricklink.marketplace_sync.listing_create_blocked_lot_hide_failed marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} stockRoomId={} message={}",
                    request.getMarketplaceListingSyncRequestId(),
                    request.getMarketplaceListingId(),
                    remoteInventoryId,
                    stockroom,
                    e.getMessage(),
                    e
            );
            return "; hiding lot " + remoteInventoryId + " in stockroom " + stockroom + " failed, hide it by hand: " + e.getMessage();
        }
    }

    /**
     * Puts requests whose worker died mid-run (pod killed, process crashed) back to PENDING, or to
     * FAILED when they have no attempts left. Without this they stay CLAIMED forever, and
     * lego-data-service refuses to queue a new LISTING_CREATE for the listing. Safe because a
     * resumed LISTING_CREATE never creates a second lot: it resumes from a saved id or finds the
     * lot by its remarks. Runs once per run, before anything is claimed, and the job holds a
     * ShedLock lock, so no other worker can be holding these claims.
     */
    private int releaseStaleClaims() {
        ZonedDateTime cutoff = now().minus(properties.effectiveStaleClaimTimeout());
        int released = 0;
        for (MarketplaceListingSyncRequest request : marketplaceListingSyncRequestDao.findBySyncRequestStatusCode(STATUS_CLAIMED)) {
            if (!Set.of(TYPE_PRICE_UPDATE, TYPE_LISTING_CREATE).contains(normalize(request.getSyncRequestTypeCode()))) {
                continue;
            }
            if (request.getClaimedAt() == null || request.getClaimedAt().isAfter(cutoff)) {
                continue;
            }
            ZonedDateTime claimedAt = request.getClaimedAt();
            request.setClaimedAt(null);
            request.setLastErrorMessage(shortMessage("Released a stale claim from " + claimedAt + ": the worker stopped before finishing"));
            if (request.getAttemptCount() != null && request.getMaxAttempts() != null && request.getAttemptCount() >= request.getMaxAttempts()) {
                request.setSyncRequestStatusCode(STATUS_FAILED);
                request.setCompletedAt(now());
            } else {
                request.setSyncRequestStatusCode(STATUS_PENDING);
                request.setNextAttemptAt(now());
            }
            marketplaceListingSyncRequestDao.update(request);
            released++;
            log.warn(
                    "bricklink.marketplace_sync.stale_claim_released marketplaceListingSyncRequestId={} marketplaceListingId={} syncRequestType={} claimedAt={} newStatus={} remoteInventoryId={}",
                    request.getMarketplaceListingSyncRequestId(),
                    request.getMarketplaceListingId(),
                    request.getSyncRequestTypeCode(),
                    claimedAt,
                    request.getSyncRequestStatusCode(),
                    request.getRemoteInventoryId()
            );
        }
        return released;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private SyncContext context(MarketplaceListingSyncRequest request, boolean requireBricklinkInventoryId) {
        MarketplaceListing listing = marketplaceListingDao.findByMarketplaceListingId(request.getMarketplaceListingId())
                .orElseThrow(() -> new IllegalStateException("Marketplace listing not found"));
        if (!properties.getBricklinkExternalServiceId().equals(listing.getListingExternalServiceId())) {
            throw new IllegalStateException("Marketplace listing does not belong to configured BrickLink external service");
        }
        BricklinkMarketplaceListing bricklinkListing = bricklinkMarketplaceListingDao.findByMarketplaceListingId(request.getMarketplaceListingId())
                .orElseThrow(() -> new IllegalStateException("BrickLink listing mapping not found"));
        ItemInventory itemInventory = itemInventoryDao.findByItemInventoryId(listing.getItemInventoryId())
                .orElseThrow(() -> new IllegalStateException("Item inventory not found"));
        Integer bricklinkInventoryId = bricklinkListing.getBricklinkInventoryId();
        if (requireBricklinkInventoryId && bricklinkInventoryId == null) {
            throw new IllegalStateException("BrickLink inventory id is missing");
        }
        if (bricklinkInventoryId != null && request.getRemoteInventoryId() != null && !request.getRemoteInventoryId().equals(bricklinkInventoryId.toString())) {
            throw new IllegalStateException("Sync request remote inventory id does not match BrickLink listing mapping");
        }
        return new SyncContext(listing, bricklinkListing, itemInventory, bricklinkInventoryId);
    }

    private void updateRemoteInventory(SyncContext context, MarketplaceListingSyncRequest request, BricklinkRemoteInventorySafetyResult safety) {
        Inventory update = new Inventory();
        update.setUnit_price(price(request.getRequestedUnitPrice()));
        update.setRemarks(safety.desiredRemarks());
        if (properties.nonProdEnvironment()) {
            update.setIs_stock_room(true);
            update.setStock_room_id(properties.effectiveNonProdStockRoomId());
        }
        bricklinkRestClient.updateInventory(context.bricklinkInventoryId().longValue(), update);
        log.info(
                "bricklink.marketplace_sync.remote_updated marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} requestedPrice={}",
                request.getMarketplaceListingSyncRequestId(),
                request.getMarketplaceListingId(),
                context.bricklinkInventoryId(),
                price(request.getRequestedUnitPrice())
        );
    }

    private void updateCreateSuccessLocalState(
            SyncContext context,
            MarketplaceListingSyncRequest request,
            BricklinkListingCreateSafetyResult safety,
            Inventory verifiedRemoteInventory,
            Long remoteInventoryId
    ) {
        ZonedDateTime synchronizedAt = now();
        MarketplaceListing listing = context.listing();
        listing.setExternalListingId(remoteInventoryId.toString());
        listing.setListingStatusCode(LISTING_STATUS_ACTIVE);
        listing.setPublishedAt(synchronizedAt);
        listing.setLastSynchronizedAt(synchronizedAt);
        marketplaceListingDao.update(listing);

        updateCreateSafetyMetadata(context.bricklinkListing(), safety, verifiedRemoteInventory);
        request.setRemoteInventoryId(remoteInventoryId.toString());
        request.setRemoteVisibilityScopeCode(safety.stockRoom() ? BricklinkPricingApplyService.REMOTE_SCOPE_STOCKROOM : BricklinkPricingApplyService.REMOTE_SCOPE_PUBLIC);
        request.setRemoteVisibilityContainerId(safety.stockRoomId());
        request.setRemoteIsPubliclyAvailable(safety.publiclyAvailable());
    }

    private void updateCreateSafetyMetadata(
            BricklinkMarketplaceListing bricklinkListing,
            BricklinkListingCreateSafetyResult safety,
            Inventory remoteInventory
    ) {
        bricklinkListing.setEnvironmentCode(properties.effectiveEnvironmentCode());
        bricklinkListing.setSystemRemarksHash(safety.desiredRemarksHash());
        bricklinkListing.setLastRemoteVerifiedAt(now());
        bricklinkListing.setLastRemoteSafetyStatusCode(safety.statusCode());
        bricklinkListing.setLastRemoteSafetyMessage(safety.message());
        bricklinkListing.setRemarks(safety.desiredRemarks());
        bricklinkListing.setIsStockRoom(safety.stockRoom());
        bricklinkListing.setStockRoomId(safety.stockRoomId());
        if (remoteInventory != null) {
            bricklinkListing.setBricklinkInventoryId(remoteInventory.getInventory_id() == null ? null : remoteInventory.getInventory_id().intValue());
            bricklinkListing.setBricklinkDateCreated(zonedDateTime(remoteInventory.getDate_created()));
            bricklinkListing.setLastRemoteQuantity(remoteInventory.getQuantity());
            bricklinkListing.setIsStockRoom(remoteInventory.getIs_stock_room());
            bricklinkListing.setStockRoomId(remoteInventory.getStock_room_id());
            bricklinkListing.setRemarks(remoteInventory.getRemarks());
        }
        bricklinkMarketplaceListingDao.update(bricklinkListing);
    }

    private void updateSafetyMetadata(
            BricklinkMarketplaceListing bricklinkListing,
            BricklinkRemoteInventorySafetyResult safety,
            Inventory remoteInventory
    ) {
        bricklinkListing.setEnvironmentCode(properties.effectiveEnvironmentCode());
        bricklinkListing.setSystemRemarksHash(safety.desiredRemarksHash());
        bricklinkListing.setLastRemoteVerifiedAt(now());
        bricklinkListing.setLastRemoteSafetyStatusCode(safety.statusCode());
        bricklinkListing.setLastRemoteSafetyMessage(safety.message());
        if (remoteInventory != null) {
            bricklinkListing.setIsStockRoom(remoteInventory.getIs_stock_room());
            bricklinkListing.setStockRoomId(remoteInventory.getStock_room_id());
            bricklinkListing.setRemarks(safety.desiredRemarks() == null ? remoteInventory.getRemarks() : safety.desiredRemarks());
        }
        bricklinkMarketplaceListingDao.update(bricklinkListing);
    }

    private void markSucceeded(MarketplaceListingSyncRequest request, BricklinkRemoteInventorySafetyResult safety) {
        request.setSyncRequestStatusCode(STATUS_SUCCEEDED);
        request.setLastErrorMessage(null);
        request.setClaimedAt(null);
        request.setCompletedAt(now());
        request.setRemoteVisibilityScopeCode(properties.nonProdEnvironment() ? BricklinkPricingApplyService.REMOTE_SCOPE_STOCKROOM : request.getRemoteVisibilityScopeCode());
        request.setRemoteVisibilityContainerId(properties.nonProdEnvironment() ? properties.effectiveNonProdStockRoomId() : request.getRemoteVisibilityContainerId());
        request.setRemoteIsPubliclyAvailable(!properties.nonProdEnvironment());
        marketplaceListingSyncRequestDao.update(request);
    }

    private void markBlocked(MarketplaceListingSyncRequest request, String statusCode, String message) {
        request.setSyncRequestStatusCode(STATUS_BLOCKED);
        request.setLastErrorMessage(shortMessage(statusCode + ": " + message));
        request.setClaimedAt(null);
        request.setCompletedAt(now());
        marketplaceListingSyncRequestDao.update(request);
    }

    private void markRetryOrFailed(MarketplaceListingSyncRequest request, String message) {
        request.setClaimedAt(null);
        request.setLastErrorMessage(shortMessage(message));
        if (request.getAttemptCount() != null && request.getMaxAttempts() != null && request.getAttemptCount() >= request.getMaxAttempts()) {
            request.setSyncRequestStatusCode(STATUS_FAILED);
            request.setCompletedAt(now());
        } else {
            request.setSyncRequestStatusCode(STATUS_PENDING);
            request.setNextAttemptAt(now().plus(properties.effectiveRetryBackoff()));
        }
        marketplaceListingSyncRequestDao.update(request);
    }

    private String shortMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private Double price(BigDecimal price) {
        return price == null ? null : price.doubleValue();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim().toUpperCase();
    }

    private ZonedDateTime zonedDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneOffset.UTC);
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    private long elapsedMillis(long start) {
        return System.currentTimeMillis() - start;
    }

    private <T> T data(BricklinkResource<T> resource) {
        return resource == null ? null : resource.getData();
    }

    private record SyncContext(
            MarketplaceListing listing,
            BricklinkMarketplaceListing bricklinkListing,
            ItemInventory itemInventory,
            Integer bricklinkInventoryId
    ) {
    }

    private static final class Counters {
        private final int selected;
        private int requestsClaimed;
        private int remoteVerified;
        private int remoteUpdated;
        private int dryRunVerified;
        private int blocked;
        private int failed;

        private Counters(int selected) {
            this.selected = selected;
        }

        private BricklinkMarketplaceSyncResult result(BricklinkMarketplaceSyncMode mode, long elapsedMillis) {
            String outcome;
            if (failed > 0 && remoteUpdated == 0 && dryRunVerified == 0) {
                outcome = "FAILED";
            } else if (failed > 0 || blocked > 0) {
                outcome = "PARTIAL_SUCCESS";
            } else if (remoteUpdated == 0 && dryRunVerified == 0) {
                outcome = "NO_SYNCABLE_REQUESTS";
            } else {
                outcome = "SUCCESS";
            }
            return new BricklinkMarketplaceSyncResult(
                    outcome,
                    mode,
                    selected,
                    requestsClaimed,
                    remoteVerified,
                    remoteUpdated,
                    dryRunVerified,
                    blocked,
                    failed,
                    elapsedMillis
            );
        }
    }
}
