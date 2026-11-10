package io.legohunter.ingress.pricing;

import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dao.PricingApplyReadinessDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.data.dto.PricingApplyReadinessReview;
import io.legohunter.data.dto.PricingDecision;
import io.legohunter.ingress.marketplace.bricklink.BricklinkMarketplaceSyncProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.pricing.apply", name = "enabled", havingValue = "true")
public class BricklinkPricingApplyService {
    static final String READY_TO_APPLY = BricklinkPricingApplyReadinessService.READY_TO_APPLY;
    static final String READY_TO_APPLY_INITIAL_PRICE = BricklinkPricingApplyReadinessService.READY_TO_APPLY_INITIAL_PRICE;
    static final String SYNC_TYPE_LISTING_CREATE = "LISTING_CREATE";
    static final String SYNC_TYPE_PRICE_UPDATE = "PRICE_UPDATE";
    static final String SYNC_STATUS_PENDING = "PENDING";
    static final String SYNC_REASON_PRICING_DECISION_APPLIED = "PRICING_DECISION_APPLIED";
    static final String SYNC_REASON_INITIAL_PRICE_APPLIED = "INITIAL_PRICE_APPLIED";
    public static final String REMOTE_SCOPE_STOCKROOM = "STOCKROOM";
    public static final String REMOTE_SCOPE_PUBLIC = "PUBLIC";
    static final String LISTING_STATUS_DRAFT = "DRAFT";
    static final String STATUS_PROPOSED = BricklinkPricingDecisionService.STATUS_PROPOSED;
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_APPLIED = "APPLIED";
    /** ACCEPTED, but its sync request ended BLOCKED or FAILED, so BrickLink never got the price. */
    public static final String STATUS_SYNC_FAILED = "SYNC_FAILED";
    private static final Set<String> OPEN_SYNC_STATUSES = Set.of("PENDING", "CLAIMED");
    private static final String JOB_NAME = "BricklinkPricingApplyJob";

    private final BricklinkPricingApplyProperties properties;
    private final PricingApplyReadinessDao pricingApplyReadinessDao;
    private final PricingDecisionDao pricingDecisionDao;
    private final MarketplaceListingDao marketplaceListingDao;
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao;
    private final MarketplaceListingSyncRequestDao marketplaceListingSyncRequestDao;
    private final BricklinkMarketplaceSyncProperties marketplaceSyncProperties;
    private final TransactionOperations transactions;

    public BricklinkPricingApplyResult runOnce() {
        long start = System.currentTimeMillis();
        BricklinkPricingApplyMode mode = properties.effectiveMode();
        Set<PricingApplyReadinessReview> reviews = pricingApplyReadinessDao.findLatestReadyToApplyReviews(properties.effectiveBatchSize());
        if (reviews.isEmpty()) {
            return BricklinkPricingApplyResult.noWork(mode != BricklinkPricingApplyMode.DRY_RUN, mode, elapsedMillis(start));
        }

        Counters counters = new Counters(reviews.size());
        for (PricingApplyReadinessReview review : reviews) {
            try {
                ApplyCandidate candidate = candidate(review);
                if (!candidate.priceChanged()) {
                    counters.skippedRows++;
                    log.info(
                            "bricklink.pricing.apply.skipped marketplaceListingId={} pricingDecisionId={} reason=PRICE_ALREADY_CURRENT currentPrice={} proposedPrice={} mode={}",
                            review.getMarketplaceListingId(),
                            review.getPricingDecisionId(),
                            money(candidate.listing().getUnitPrice()),
                            money(review.getProposedPrice()),
                            mode
                    );
                    continue;
                }

                if (mode == BricklinkPricingApplyMode.DRY_RUN) {
                    counters.dryRunSelections++;
                    log.info(
                            "bricklink.pricing.apply.dry_run marketplaceListingId={} pricingDecisionId={} currentPrice={} proposedPrice={} currencyCode={} reasonCode={}",
                            review.getMarketplaceListingId(),
                            review.getPricingDecisionId(),
                            money(candidate.listing().getUnitPrice()),
                            money(review.getProposedPrice()),
                            review.getCurrencyCode(),
                            review.getDecisionReasonCode()
                    );
                    continue;
                }

                if (mode == BricklinkPricingApplyMode.APPLY_LOCAL_ONLY) {
                    transactions.executeWithoutResult(status -> applyLocalPriceOnly(candidate, review));
                    counters.localPricesUpdated++;
                    continue;
                }

                BricklinkMarketplaceListing bricklinkListing = bricklinkMarketplaceListingDao
                        .findByMarketplaceListingId(candidate.listing().getMarketplaceListingId())
                        .orElse(null);
                if (isInitialPriceReadiness(review) && hasRemoteInventoryMapping(bricklinkListing)) {
                    throw new IllegalStateException("Initial pricing readiness cannot apply to a listing with remote BrickLink inventory");
                }
                if (hasOpenSyncRequest(candidate.listing().getMarketplaceListingId())) {
                    // One price change in flight per listing: a newer decision waits until BrickLink
                    // has confirmed or refused the previous one, so it is measured from the real price.
                    counters.skippedRows++;
                    log.info(
                            "bricklink.pricing.apply.skipped marketplaceListingId={} pricingDecisionId={} reason=SYNC_REQUEST_IN_FLIGHT",
                            review.getMarketplaceListingId(),
                            review.getPricingDecisionId()
                    );
                    continue;
                }

                if (hasRemoteInventoryMapping(bricklinkListing)) {
                    // Remote first: the local price changes only when the sync worker has confirmed
                    // the new price on BrickLink.
                    transactions.executeWithoutResult(status -> {
                        acceptDecision(candidate);
                        enqueuePriceUpdateSyncRequest(candidate, review, bricklinkListing);
                    });
                    counters.syncRequestsEnqueued++;
                } else if (LISTING_STATUS_DRAFT.equals(normalize(candidate.listing().getListingStatusCode()))) {
                    // A draft has no BrickLink lot yet, so its local price cannot drift from one. The
                    // create request is built from it and must match it.
                    transactions.executeWithoutResult(status -> {
                        updateDraftPrice(candidate, review);
                        acceptDecision(candidate);
                        enqueueListingCreateSyncRequest(candidate, review, bricklinkListing);
                    });
                    counters.localPricesUpdated++;
                    counters.syncRequestsEnqueued++;
                } else {
                    counters.skippedRows++;
                    log.info(
                            "bricklink.pricing.apply.skipped marketplaceListingId={} pricingDecisionId={} reason=MISSING_REMOTE_INVENTORY_ID_FOR_NON_DRAFT",
                            candidate.listing().getMarketplaceListingId(),
                            candidate.decision().getPricingDecisionId()
                    );
                }
            } catch (AlreadyAppliedPricingDecisionException e) {
                counters.skippedRows++;
                log.info(
                        "bricklink.pricing.apply.skipped marketplaceListingId={} pricingDecisionId={} reason=PRICING_DECISION_ALREADY_APPLIED",
                        review.getMarketplaceListingId(),
                        review.getPricingDecisionId()
                );
            } catch (RuntimeException e) {
                counters.failedRows++;
                log.warn(
                        "bricklink.pricing.apply.failed marketplaceListingId={} pricingDecisionId={} message={}",
                        review.getMarketplaceListingId(),
                        review.getPricingDecisionId(),
                        e.getMessage(),
                        e
                );
            }
        }

        return counters.result(mode, elapsedMillis(start));
    }

    private ApplyCandidate candidate(PricingApplyReadinessReview review) {
        if (!isReadyToApply(review)) {
            throw new IllegalStateException("Readiness row is not READY_TO_APPLY");
        }
        if (Boolean.TRUE.equals(review.getFixedPrice())) {
            throw new IllegalStateException("Fixed-price listing cannot be applied by Pricing Plane");
        }
        MarketplaceListing listing = marketplaceListingDao.findByMarketplaceListingId(review.getMarketplaceListingId())
                .orElseThrow(() -> new IllegalStateException("Marketplace listing not found"));
        PricingDecision decision = pricingDecisionDao.findByPricingDecisionId(review.getPricingDecisionId())
                .orElseThrow(() -> new IllegalStateException("Pricing decision not found"));
        if (decision.getAppliedAt() != null) {
            throw new AlreadyAppliedPricingDecisionException();
        }
        if (!"PROPOSED".equals(normalize(decision.getDecisionStatusCode()))) {
            throw new IllegalStateException("Pricing decision is not PROPOSED");
        }
        if (!review.getPricingDecisionId().equals(decision.getPricingDecisionId())) {
            throw new IllegalStateException("Readiness row does not match pricing decision");
        }
        if (review.getProposedPrice() == null || decision.getFinalPrice() == null
                || review.getProposedPrice().signum() <= 0 || decision.getFinalPrice().signum() <= 0) {
            throw new IllegalStateException("Proposed price is missing");
        }
        if (!money(review.getProposedPrice()).equals(money(decision.getFinalPrice()))) {
            throw new IllegalStateException("Readiness proposed price no longer matches pricing decision final price");
        }
        if (!sameCurrency(listing.getCurrencyCode(), decision.getCurrencyCode())) {
            throw new IllegalStateException("Marketplace listing currency no longer matches decision currency");
        }
        if (listing.getFixedPrice() != null && listing.getFixedPrice()) {
            throw new IllegalStateException("Marketplace listing was changed to fixed-price after readiness review");
        }
        if (isInitialPriceReadiness(review)
                && (!LISTING_STATUS_DRAFT.equals(normalize(listing.getListingStatusCode()))
                || listing.getUnitPrice() != null
                || !blank(listing.getExternalListingId()))) {
            throw new IllegalStateException("Initial pricing readiness is stale because the draft now has a listing price or remote listing id");
        }
        return new ApplyCandidate(listing, decision);
    }

    /**
     * {@code APPLY_LOCAL_ONLY}: no BrickLink write is wanted, so the decision is applied to the local
     * price straight away.
     */
    private void applyLocalPriceOnly(ApplyCandidate candidate, PricingApplyReadinessReview review) {
        ZonedDateTime now = now();
        updateLocalPrice(candidate, review, now);
        pricingDecisionDao.transitionStatus(candidate.decision().getPricingDecisionId(), STATUS_PROPOSED, STATUS_APPLIED, now)
                .orElseThrow(AlreadyAppliedPricingDecisionException::new);
        log.info(
                "bricklink.pricing.apply.local_updated marketplaceListingId={} pricingDecisionId={} previousPrice={} appliedPrice={} currencyCode={}",
                candidate.listing().getMarketplaceListingId(),
                candidate.decision().getPricingDecisionId(),
                money(candidate.listing().getUnitPrice()),
                money(review.getProposedPrice()),
                review.getCurrencyCode()
        );
    }

    private void updateDraftPrice(ApplyCandidate candidate, PricingApplyReadinessReview review) {
        updateLocalPrice(candidate, review, now());
        log.info(
                "bricklink.pricing.apply.draft_price_set marketplaceListingId={} pricingDecisionId={} draftPrice={} currencyCode={}",
                candidate.listing().getMarketplaceListingId(),
                candidate.decision().getPricingDecisionId(),
                money(review.getProposedPrice()),
                review.getCurrencyCode()
        );
    }

    private void updateLocalPrice(ApplyCandidate candidate, PricingApplyReadinessReview review, ZonedDateTime now) {
        marketplaceListingDao.updateUnitPrice(candidate.listing().getMarketplaceListingId(), money(review.getProposedPrice()), now)
                .orElseThrow(() -> new IllegalStateException("Marketplace listing price update did not affect a row"));
    }

    /**
     * PROPOSED to ACCEPTED: the decision is handed to the sync worker, which moves it to APPLIED once
     * BrickLink has the price. Throws when another worker accepted it first, which rolls back the
     * surrounding transaction.
     */
    private void acceptDecision(ApplyCandidate candidate) {
        pricingDecisionDao.transitionStatus(candidate.decision().getPricingDecisionId(), STATUS_PROPOSED, STATUS_ACCEPTED, null)
                .orElseThrow(AlreadyAppliedPricingDecisionException::new);
    }

    private boolean hasOpenSyncRequest(Integer marketplaceListingId) {
        return marketplaceListingSyncRequestDao.findByMarketplaceListingId(marketplaceListingId).stream()
                .map(MarketplaceListingSyncRequest::getSyncRequestStatusCode)
                .map(this::normalize)
                .anyMatch(OPEN_SYNC_STATUSES::contains);
    }

    /**
     * Inserts a new sync request. Never overwrites one: a request for this listing, decision and type
     * that already exists may be CLAIMED or finished, and resetting it to PENDING could push a price
     * twice or undo a result.
     */
    private void insertSyncRequest(MarketplaceListingSyncRequest request) {
        if (marketplaceListingSyncRequestDao.findByMarketplaceListingIdAndPricingDecisionIdAndSyncRequestTypeCode(
                request.getMarketplaceListingId(),
                request.getPricingDecisionId(),
                request.getSyncRequestTypeCode()
        ).isPresent()) {
            throw new IllegalStateException("A " + request.getSyncRequestTypeCode() + " sync request already exists for this pricing decision");
        }
        marketplaceListingSyncRequestDao.insert(request);
    }

    private void enqueuePriceUpdateSyncRequest(
            ApplyCandidate candidate,
            PricingApplyReadinessReview review,
            BricklinkMarketplaceListing bricklinkListing
    ) {
        Integer bricklinkInventoryId = bricklinkListing.getBricklinkInventoryId();
        if (bricklinkInventoryId == null) {
            throw new IllegalStateException("BrickLink inventory id is missing");
        }
        MarketplaceListingSyncRequest request = MarketplaceListingSyncRequest.builder()
                .marketplaceListingId(candidate.listing().getMarketplaceListingId())
                .listingExternalServiceId(properties.getBricklinkExternalServiceId())
                .pricingDecisionId(candidate.decision().getPricingDecisionId())
                .pricingApplyReadinessId(review.getPricingApplyReadinessId())
                .syncRequestTypeCode(SYNC_TYPE_PRICE_UPDATE)
                .syncRequestStatusCode(SYNC_STATUS_PENDING)
                .syncReasonCode(syncReasonCode(review))
                .previousUnitPrice(money(candidate.listing().getUnitPrice()))
                .requestedUnitPrice(money(review.getProposedPrice()))
                .currencyCode(review.getCurrencyCode())
                .remoteInventoryId(bricklinkInventoryId.toString())
                .remoteVisibilityScopeCode(Boolean.TRUE.equals(bricklinkListing.getIsStockRoom()) ? REMOTE_SCOPE_STOCKROOM : REMOTE_SCOPE_PUBLIC)
                .remoteVisibilityContainerId(bricklinkListing.getStockRoomId())
                .remoteIsPubliclyAvailable(!Boolean.TRUE.equals(bricklinkListing.getIsStockRoom()))
                .environmentCode(properties.effectiveEnvironmentCode())
                .createdByJobName(JOB_NAME)
                .attemptCount(0)
                .maxAttempts(properties.effectiveSyncRequestMaxAttempts())
                .nextAttemptAt(now())
                .appliedLocalAt(null)
                .build();
        insertSyncRequest(request);
        log.info(
                "bricklink.pricing.apply.sync_enqueued marketplaceListingId={} pricingDecisionId={} bricklinkInventoryId={} requestedPrice={} currencyCode={}",
                candidate.listing().getMarketplaceListingId(),
                candidate.decision().getPricingDecisionId(),
                bricklinkInventoryId,
                money(review.getProposedPrice()),
                review.getCurrencyCode()
        );
    }

    private void enqueueListingCreateSyncRequest(
            ApplyCandidate candidate,
            PricingApplyReadinessReview review,
            BricklinkMarketplaceListing bricklinkListing
    ) {
        boolean nonProd = marketplaceSyncProperties.nonProdEnvironment();
        MarketplaceListingSyncRequest request = MarketplaceListingSyncRequest.builder()
                .marketplaceListingId(candidate.listing().getMarketplaceListingId())
                .listingExternalServiceId(properties.getBricklinkExternalServiceId())
                .pricingDecisionId(candidate.decision().getPricingDecisionId())
                .pricingApplyReadinessId(review.getPricingApplyReadinessId())
                .syncRequestTypeCode(SYNC_TYPE_LISTING_CREATE)
                .syncRequestStatusCode(SYNC_STATUS_PENDING)
                .syncReasonCode(syncReasonCode(review))
                .previousUnitPrice(money(candidate.listing().getUnitPrice()))
                .requestedUnitPrice(money(review.getProposedPrice()))
                .currencyCode(review.getCurrencyCode())
                .remoteInventoryId(null)
                .remoteVisibilityScopeCode(nonProd ? REMOTE_SCOPE_STOCKROOM : requestedVisibilityScope(bricklinkListing))
                .remoteVisibilityContainerId(nonProd ? marketplaceSyncProperties.effectiveNonProdStockRoomId() : requestedVisibilityContainerId(bricklinkListing))
                .remoteIsPubliclyAvailable(nonProd ? false : !REMOTE_SCOPE_STOCKROOM.equals(requestedVisibilityScope(bricklinkListing)))
                .environmentCode(marketplaceSyncProperties.effectiveEnvironmentCode())
                .createdByJobName(JOB_NAME)
                .attemptCount(0)
                .maxAttempts(properties.effectiveSyncRequestMaxAttempts())
                .nextAttemptAt(now())
                .appliedLocalAt(now())
                .build();
        insertSyncRequest(request);
        log.info(
                "bricklink.pricing.apply.listing_create_sync_enqueued marketplaceListingId={} pricingDecisionId={} requestedPrice={} currencyCode={} remoteVisibilityScopeCode={} environmentCode={}",
                candidate.listing().getMarketplaceListingId(),
                candidate.decision().getPricingDecisionId(),
                money(review.getProposedPrice()),
                review.getCurrencyCode(),
                request.getRemoteVisibilityScopeCode(),
                request.getEnvironmentCode()
        );
    }

    private boolean hasRemoteInventoryMapping(BricklinkMarketplaceListing bricklinkListing) {
        return bricklinkListing != null && bricklinkListing.getBricklinkInventoryId() != null;
    }

    private boolean isReadyToApply(PricingApplyReadinessReview review) {
        String statusCode = normalize(review.getReadinessStatusCode());
        return READY_TO_APPLY.equals(statusCode) || READY_TO_APPLY_INITIAL_PRICE.equals(statusCode);
    }

    private boolean isInitialPriceReadiness(PricingApplyReadinessReview review) {
        return READY_TO_APPLY_INITIAL_PRICE.equals(normalize(review.getReadinessStatusCode()));
    }

    private String syncReasonCode(PricingApplyReadinessReview review) {
        return isInitialPriceReadiness(review) ? SYNC_REASON_INITIAL_PRICE_APPLIED : SYNC_REASON_PRICING_DECISION_APPLIED;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String requestedVisibilityScope(BricklinkMarketplaceListing bricklinkListing) {
        return bricklinkListing != null && Boolean.TRUE.equals(bricklinkListing.getIsStockRoom())
                ? REMOTE_SCOPE_STOCKROOM
                : REMOTE_SCOPE_PUBLIC;
    }

    private String requestedVisibilityContainerId(BricklinkMarketplaceListing bricklinkListing) {
        return bricklinkListing == null ? null : bricklinkListing.getStockRoomId();
    }

    private boolean sameCurrency(String left, String right) {
        return normalizeCurrency(left).equals(normalizeCurrency(right));
    }

    private String normalizeCurrency(String value) {
        if (value == null || value.isBlank()) {
            return "USD";
        }
        return value.trim().toUpperCase();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim().toUpperCase();
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

    private record ApplyCandidate(MarketplaceListing listing, PricingDecision decision) {
        private boolean priceChanged() {
            return listing.getUnitPrice() == null || decision.getFinalPrice() == null
                    || listing.getUnitPrice().setScale(2, RoundingMode.HALF_UP)
                    .compareTo(decision.getFinalPrice().setScale(2, RoundingMode.HALF_UP)) != 0;
        }
    }

    private static final class AlreadyAppliedPricingDecisionException extends RuntimeException {
        private AlreadyAppliedPricingDecisionException() {
            super("Pricing decision is already applied");
        }
    }

    private static final class Counters {
        private final int selected;
        private int localPricesUpdated;
        private int syncRequestsEnqueued;
        private int dryRunSelections;
        private int skippedRows;
        private int failedRows;

        private Counters(int selected) {
            this.selected = selected;
        }

        private BricklinkPricingApplyResult result(BricklinkPricingApplyMode mode, long elapsedMillis) {
            String outcome;
            boolean didWork = localPricesUpdated > 0 || syncRequestsEnqueued > 0 || dryRunSelections > 0;
            if (failedRows > 0 && !didWork) {
                outcome = "FAILED";
            } else if (failedRows > 0) {
                outcome = "PARTIAL_SUCCESS";
            } else if (!didWork) {
                outcome = "NO_APPLICABLE_DECISIONS";
            } else {
                outcome = "SUCCESS";
            }
            return new BricklinkPricingApplyResult(
                    outcome,
                    mode != BricklinkPricingApplyMode.DRY_RUN,
                    mode,
                    selected,
                    localPricesUpdated,
                    syncRequestsEnqueued,
                    dryRunSelections,
                    skippedRows,
                    failedRows,
                    elapsedMillis
            );
        }
    }
}
