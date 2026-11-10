package io.legohunter.ingress.pricing;

import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.data.dto.PricingDecision;
import io.legohunter.ingress.marketplace.MarketplaceSyncPort;
import io.legohunter.ingress.marketplace.bricklink.BricklinkMarketplaceSyncProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Reprice pass (HOMELAB-23 design 4.2, built in HOMELAB-65): for each candidate listing, in one
 * transaction, decide a price, guard it, record the decision with the guard outcome and, in APPLY mode,
 * accept it and queue its sync request. After the commit the request goes straight to the marketplace's
 * sync worker ({@link MarketplaceSyncPort}); if that fails it stays PENDING for the sync job.
 *
 * <p>Replaces the decision, apply-readiness and apply jobs: no readiness rows, no stale-decision check.
 * Every check that protects BrickLink stays in the sync worker, unchanged.
 *
 * <p>Decision statuses written here: PROPOSED (guard passed, DRY_RUN), BLOCKED (guard said no),
 * SKIPPED (fixed price), FAILED (no price), ACCEPTED (request queued), APPLIED (APPLY_LOCAL_ONLY).
 * A listing with a change already in flight gets no decision: it stays a candidate and is decided
 * again, from the price the marketplace then holds, once the change finishes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.pricing.reprice", name = "enabled", havingValue = "true")
public class RepriceService {
    public static final String STATUS_BLOCKED = "BLOCKED";
    static final String JOB_NAME = "RepriceJob";
    private static final Set<String> OPEN_SYNC_STATUSES = Set.of("PENDING", "CLAIMED");

    private final RepriceProperties properties;
    private final BricklinkPricingDecisionProperties decisionProperties;
    private final BricklinkPricingApplyReadinessProperties guardProperties;
    private final BricklinkMarketplaceSyncProperties marketplaceSyncProperties;
    private final BricklinkPricingDecider decider;
    private final MarketplaceListingDao marketplaceListingDao;
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao;
    private final PricingDecisionDao pricingDecisionDao;
    private final MarketplaceListingSyncRequestDao marketplaceListingSyncRequestDao;
    private final ObjectProvider<MarketplaceSyncPort> syncPorts;
    private final TransactionOperations transactions;

    public RepriceResult runOnce() {
        long start = System.currentTimeMillis();
        RepriceMode mode = properties.effectiveMode();
        Set<MarketplaceListing> listings = marketplaceListingDao.findPricingDecisionCandidatesByListingExternalServiceIdAndListingStatusCodes(
                decisionProperties.getBricklinkExternalServiceId(),
                decisionProperties.effectivePriceableListingStatusCodes(),
                properties.effectiveBatchSize(),
                decisionProperties.isRequireCurrentSnapshot()
        );
        if (listings.isEmpty()) {
            return RepriceResult.noWork(mode, elapsedMillis(start));
        }

        Counters counters = new Counters(listings.size());
        List<Queued> queued = new ArrayList<>();
        for (MarketplaceListing listing : listings) {
            try {
                Outcome outcome = reprice(listing, mode);
                counters.record(outcome);
                if (outcome.syncRequestId() != null) {
                    queued.add(new Queued(listing.getMarketplaceListingId(), outcome.syncRequestId()));
                }
            } catch (RuntimeException e) {
                counters.errors++;
                log.warn(
                        "pricing.reprice.failed marketplaceListingId={} mode={} message={}",
                        listing.getMarketplaceListingId(),
                        mode,
                        e.getMessage(),
                        e
                );
            }
        }
        handOff(queued, counters);
        return counters.result(mode, elapsedMillis(start));
    }

    private Outcome reprice(MarketplaceListing listing, RepriceMode mode) {
        if (mode != RepriceMode.DRY_RUN && hasOpenSyncRequest(listing.getMarketplaceListingId())) {
            // One change in flight per listing: decide again once the marketplace has confirmed or
            // refused it, so the next decision is measured from the price it really holds.
            log.info("pricing.reprice.waiting_for_sync marketplaceListingId={}", listing.getMarketplaceListingId());
            return Outcome.WAITING;
        }

        PricingDecision decision = decider.decide(listing);
        if (!BricklinkPricingDecider.STATUS_PROPOSED.equals(decision.getDecisionStatusCode())) {
            pricingDecisionDao.insert(decision);
            logDecision(listing, decision, null);
            return new Outcome(decision.getDecisionStatusCode(), null);
        }

        BricklinkMarketplaceListing bricklinkListing = bricklinkMarketplaceListingDao
                .findByMarketplaceListingId(listing.getMarketplaceListingId())
                .orElse(null);
        boolean hasRemoteLot = bricklinkListing != null && bricklinkListing.getBricklinkInventoryId() != null;
        RepriceGuard.Result guard = RepriceGuard.evaluate(listing, hasRemoteLot, decision, guardProperties);
        decision.setGuardStatusCode(guard.status());
        decision.setGuardReasonCode(guard.reasonCode());

        if (!guard.isPassed()) {
            decision.setDecisionStatusCode(STATUS_BLOCKED);
            pricingDecisionDao.insert(decision);
            logDecision(listing, decision, null);
            return new Outcome(STATUS_BLOCKED, null);
        }
        return switch (mode) {
            case DRY_RUN -> {
                pricingDecisionDao.insert(decision);
                logDecision(listing, decision, null);
                yield new Outcome(BricklinkPricingDecider.STATUS_PROPOSED, null);
            }
            case APPLY_LOCAL_ONLY -> {
                transactions.executeWithoutResult(status -> {
                    ZonedDateTime now = now();
                    decision.setDecisionStatusCode(BricklinkPricingApplyService.STATUS_APPLIED);
                    decision.setAppliedAt(now);
                    pricingDecisionDao.insert(decision);
                    updateLocalPrice(listing, decision, now);
                });
                logDecision(listing, decision, null);
                yield new Outcome(BricklinkPricingApplyService.STATUS_APPLIED, null);
            }
            case APPLY -> {
                MarketplaceListingSyncRequest request = transactions.execute(status -> accept(listing, decision, bricklinkListing, hasRemoteLot, guard));
                logDecision(listing, decision, request);
                yield new Outcome(BricklinkPricingApplyService.STATUS_ACCEPTED, request.getMarketplaceListingSyncRequestId());
            }
        };
    }

    /**
     * ACCEPTED plus a sync request, in the caller's transaction. A listing with a lot gets a PRICE_UPDATE
     * and keeps its local price until the marketplace confirms the new one. A draft with no lot gets its
     * local price now and a LISTING_CREATE built from it.
     */
    private MarketplaceListingSyncRequest accept(
            MarketplaceListing listing,
            PricingDecision decision,
            BricklinkMarketplaceListing bricklinkListing,
            boolean hasRemoteLot,
            RepriceGuard.Result guard
    ) {
        decision.setDecisionStatusCode(BricklinkPricingApplyService.STATUS_ACCEPTED);
        pricingDecisionDao.insert(decision);
        MarketplaceListingSyncRequest request;
        if (hasRemoteLot) {
            request = syncRequest(listing, decision, BricklinkPricingApplyService.SYNC_TYPE_PRICE_UPDATE, guard)
                    .remoteInventoryId(bricklinkListing.getBricklinkInventoryId().toString())
                    .remoteVisibilityScopeCode(Boolean.TRUE.equals(bricklinkListing.getIsStockRoom())
                            ? BricklinkPricingApplyService.REMOTE_SCOPE_STOCKROOM : BricklinkPricingApplyService.REMOTE_SCOPE_PUBLIC)
                    .remoteVisibilityContainerId(bricklinkListing.getStockRoomId())
                    .remoteIsPubliclyAvailable(!Boolean.TRUE.equals(bricklinkListing.getIsStockRoom()))
                    .appliedLocalAt(null)
                    .build();
        } else {
            // The guard lets only a DRAFT through without a lot.
            updateLocalPrice(listing, decision, now());
            boolean nonProd = marketplaceSyncProperties.nonProdEnvironment();
            boolean stockroom = nonProd || (bricklinkListing != null && Boolean.TRUE.equals(bricklinkListing.getIsStockRoom()));
            request = syncRequest(listing, decision, BricklinkPricingApplyService.SYNC_TYPE_LISTING_CREATE, guard)
                    .remoteInventoryId(null)
                    .remoteVisibilityScopeCode(stockroom ? BricklinkPricingApplyService.REMOTE_SCOPE_STOCKROOM : BricklinkPricingApplyService.REMOTE_SCOPE_PUBLIC)
                    .remoteVisibilityContainerId(nonProd
                            ? marketplaceSyncProperties.effectiveNonProdStockRoomId()
                            : bricklinkListing == null ? null : bricklinkListing.getStockRoomId())
                    .remoteIsPubliclyAvailable(!stockroom)
                    .appliedLocalAt(now())
                    .build();
        }
        if (marketplaceListingSyncRequestDao.findByMarketplaceListingIdAndPricingDecisionIdAndSyncRequestTypeCode(
                request.getMarketplaceListingId(), request.getPricingDecisionId(), request.getSyncRequestTypeCode()).isPresent()) {
            throw new IllegalStateException("A " + request.getSyncRequestTypeCode() + " sync request already exists for this pricing decision");
        }
        return marketplaceListingSyncRequestDao.insert(request);
    }

    private MarketplaceListingSyncRequest.MarketplaceListingSyncRequestBuilder syncRequest(
            MarketplaceListing listing,
            PricingDecision decision,
            String type,
            RepriceGuard.Result guard
    ) {
        return MarketplaceListingSyncRequest.builder()
                .marketplaceListingId(listing.getMarketplaceListingId())
                .listingExternalServiceId(decisionProperties.getBricklinkExternalServiceId())
                .pricingDecisionId(decision.getPricingDecisionId())
                .pricingApplyReadinessId(null)
                .syncRequestTypeCode(type)
                .syncRequestStatusCode(BricklinkPricingApplyService.SYNC_STATUS_PENDING)
                .syncReasonCode(guard.initialPrice()
                        ? BricklinkPricingApplyService.SYNC_REASON_INITIAL_PRICE_APPLIED
                        : BricklinkPricingApplyService.SYNC_REASON_PRICING_DECISION_APPLIED)
                .previousUnitPrice(money(listing.getUnitPrice()))
                .requestedUnitPrice(money(decision.getFinalPrice()))
                .currencyCode(decision.getCurrencyCode())
                .environmentCode(marketplaceSyncProperties.effectiveEnvironmentCode())
                .createdByJobName(JOB_NAME)
                .attemptCount(0)
                .maxAttempts(properties.effectiveSyncRequestMaxAttempts())
                .nextAttemptAt(now());
    }

    /**
     * After the commits: hands each queued request to its marketplace's sync worker, up to
     * {@code immediate-sync-limit} per run. Whatever is not handed off, or fails, stays PENDING for the
     * marketplace's sync job.
     */
    private void handOff(List<Queued> queued, Counters counters) {
        int limit = properties.effectiveImmediateSyncLimit();
        for (Queued item : queued) {
            if (counters.handedOff >= limit) {
                log.info("pricing.reprice.hand_off_deferred marketplaceListingSyncRequestId={} message=immediate-sync-limit reached; the sync job will send it",
                        item.syncRequestId());
                continue;
            }
            Optional<MarketplaceSyncPort> port = syncPorts.orderedStream()
                    .filter(candidate -> candidate.handles(decisionProperties.getBricklinkExternalServiceId()))
                    .findFirst();
            if (port.isEmpty()) {
                log.info("pricing.reprice.hand_off_deferred marketplaceListingSyncRequestId={} message=marketplace sync is not enabled here; the request stays PENDING",
                        item.syncRequestId());
                continue;
            }
            try {
                MarketplaceSyncPort.HandOff handOff = port.get().processNow(item.syncRequestId());
                if (handOff.sent()) {
                    counters.handedOff++;
                    if ("SUCCEEDED".equals(handOff.requestStatus())) {
                        counters.syncConfirmed++;
                    }
                }
                log.info(
                        "pricing.reprice.handed_off marketplaceListingId={} marketplaceListingSyncRequestId={} sent={} requestStatus={}",
                        item.marketplaceListingId(),
                        item.syncRequestId(),
                        handOff.sent(),
                        handOff.requestStatus()
                );
            } catch (RuntimeException e) {
                log.warn(
                        "pricing.reprice.hand_off_failed marketplaceListingId={} marketplaceListingSyncRequestId={} message={}",
                        item.marketplaceListingId(),
                        item.syncRequestId(),
                        e.getMessage(),
                        e
                );
            }
        }
    }

    private void updateLocalPrice(MarketplaceListing listing, PricingDecision decision, ZonedDateTime now) {
        marketplaceListingDao.updateUnitPrice(listing.getMarketplaceListingId(), money(decision.getFinalPrice()), now)
                .orElseThrow(() -> new IllegalStateException("Marketplace listing price update did not affect a row"));
    }

    private boolean hasOpenSyncRequest(Integer marketplaceListingId) {
        return marketplaceListingSyncRequestDao.findByMarketplaceListingId(marketplaceListingId).stream()
                .map(MarketplaceListingSyncRequest::getSyncRequestStatusCode)
                .map(status -> status == null ? "" : status.trim().toUpperCase())
                .anyMatch(OPEN_SYNC_STATUSES::contains);
    }

    private void logDecision(MarketplaceListing listing, PricingDecision decision, MarketplaceListingSyncRequest request) {
        log.info(
                "pricing.reprice.decided marketplaceListingId={} pricingDecisionId={} status={} reasonCode={} guardStatus={} guardReason={} currentPrice={} finalPrice={} currencyCode={} syncRequestId={} syncRequestType={}",
                listing.getMarketplaceListingId(),
                decision.getPricingDecisionId(),
                decision.getDecisionStatusCode(),
                decision.getReasonCode(),
                decision.getGuardStatusCode(),
                decision.getGuardReasonCode(),
                money(listing.getUnitPrice()),
                money(decision.getFinalPrice()),
                decision.getCurrencyCode(),
                request == null ? null : request.getMarketplaceListingSyncRequestId(),
                request == null ? null : request.getSyncRequestTypeCode()
        );
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    private long elapsedMillis(long start) {
        return System.currentTimeMillis() - start;
    }

    private record Outcome(String status, Long syncRequestId) {
        static final Outcome WAITING = new Outcome("WAITING", null);
    }

    private record Queued(Integer marketplaceListingId, Long syncRequestId) {
    }

    private static final class Counters {
        private final int selected;
        private int written;
        private int proposed;
        private int blocked;
        private int skipped;
        private int failed;
        private int accepted;
        private int appliedLocally;
        private int waiting;
        private int errors;
        private int handedOff;
        private int syncConfirmed;

        private Counters(int selected) {
            this.selected = selected;
        }

        private void record(Outcome outcome) {
            if (outcome == Outcome.WAITING) {
                waiting++;
                return;
            }
            written++;
            switch (outcome.status()) {
                case BricklinkPricingDecider.STATUS_PROPOSED -> proposed++;
                case STATUS_BLOCKED -> blocked++;
                case BricklinkPricingDecider.STATUS_SKIPPED -> skipped++;
                case BricklinkPricingDecider.STATUS_FAILED -> failed++;
                case BricklinkPricingApplyService.STATUS_ACCEPTED -> accepted++;
                case BricklinkPricingApplyService.STATUS_APPLIED -> appliedLocally++;
                default -> {
                }
            }
        }

        private RepriceResult result(RepriceMode mode, long elapsedMillis) {
            String outcome;
            if (errors > 0 && written == 0) {
                outcome = "FAILED";
            } else if (errors > 0) {
                outcome = "PARTIAL_SUCCESS";
            } else {
                outcome = "SUCCESS";
            }
            return new RepriceResult(outcome, mode, selected, written, proposed, blocked, skipped, failed, accepted,
                    appliedLocally, waiting, errors, handedOff, syncConfirmed, elapsedMillis);
        }
    }
}
