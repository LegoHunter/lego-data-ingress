package io.legohunter.ingress.pricing;

import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.PricingDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * The older decision job's loop: decides each candidate listing with {@link BricklinkPricingDecider} and
 * saves the decision. Superseded by the Reprice job ({@link RepriceService}); removed in HOMELAB-66.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lego.bricklink.pricing.decision", name = "enabled", havingValue = "true")
public class BricklinkPricingDecisionService {
    public static final String STATUS_PROPOSED = BricklinkPricingDecider.STATUS_PROPOSED;
    public static final String STATUS_SKIPPED = BricklinkPricingDecider.STATUS_SKIPPED;
    public static final String STATUS_FAILED = BricklinkPricingDecider.STATUS_FAILED;
    static final String ALGORITHM_VERSION = BricklinkPricingDecider.ALGORITHM_VERSION;
    static final String REASON_FIXED_PRICE_OVERRIDE = BricklinkPricingDecider.REASON_FIXED_PRICE_OVERRIDE;
    static final String REASON_MARKET_POSITION = BricklinkPricingDecider.REASON_MARKET_POSITION;
    static final String REASON_SINGLE_COMPARABLE_DISCOUNTED = BricklinkPricingDecider.REASON_SINGLE_COMPARABLE_DISCOUNTED;
    static final String REASON_TWO_COMPARABLES_WEIGHTED = BricklinkPricingDecider.REASON_TWO_COMPARABLES_WEIGHTED;
    static final String REASON_NO_CURRENT_SNAPSHOT = BricklinkPricingDecider.REASON_NO_CURRENT_SNAPSHOT;
    static final String REASON_NO_CURRENT_COMPARABLES = BricklinkPricingDecider.REASON_NO_CURRENT_COMPARABLES;
    static final String REASON_NO_EXACT_COMPARABLES = BricklinkPricingDecider.REASON_NO_EXACT_COMPARABLES;
    static final String REASON_MISSING_INVENTORY = BricklinkPricingDecider.REASON_MISSING_INVENTORY;
    static final String REASON_MISSING_CONDITION = BricklinkPricingDecider.REASON_MISSING_CONDITION;
    static final String REASON_MISSING_COMPLETENESS = BricklinkPricingDecider.REASON_MISSING_COMPLETENESS;
    static final String REASON_BELOW_MIN_PRICE_CLAMPED = BricklinkPricingDecider.REASON_BELOW_MIN_PRICE_CLAMPED;
    static final String REASON_ABOVE_MAX_PRICE_CLAMPED = BricklinkPricingDecider.REASON_ABOVE_MAX_PRICE_CLAMPED;

    private final BricklinkPricingDecisionProperties properties;
    private final MarketplaceListingDao marketplaceListingDao;
    private final BricklinkPricingDecider decider;
    private final PricingDecisionDao pricingDecisionDao;

    public BricklinkPricingDecisionResult runOnce() {
        long start = System.currentTimeMillis();
        Set<MarketplaceListing> listings = marketplaceListingDao.findPricingDecisionCandidatesByListingExternalServiceIdAndListingStatusCodes(
                properties.getBricklinkExternalServiceId(),
                properties.effectivePriceableListingStatusCodes(),
                properties.effectiveBatchSize(),
                properties.isRequireCurrentSnapshot()
        );

        if (listings.isEmpty()) {
            return BricklinkPricingDecisionResult.noWork(elapsedMillis(start));
        }

        DecisionCounters counters = new DecisionCounters(listings.size());
        for (MarketplaceListing listing : listings) {
            PricingDecision decision = decider.decide(listing);
            pricingDecisionDao.insert(decision);
            counters.record(decision);
        }

        String outcome = counters.failedDecisions > 0 ? "PARTIAL_SUCCESS" : "SUCCESS";
        return new BricklinkPricingDecisionResult(
                outcome,
                counters.listingsSelected,
                counters.decisionsWritten,
                counters.proposedDecisions,
                counters.skippedDecisions,
                counters.failedDecisions,
                elapsedMillis(start)
        );
    }

    static BigDecimal percentile(List<BigDecimal> sortedPrices, BigDecimal fraction) {
        return BricklinkPricingDecider.percentile(sortedPrices, fraction);
    }

    private long elapsedMillis(long start) {
        return System.currentTimeMillis() - start;
    }

    private static final class DecisionCounters {
        private final int listingsSelected;
        private int decisionsWritten;
        private int proposedDecisions;
        private int skippedDecisions;
        private int failedDecisions;

        private DecisionCounters(int listingsSelected) {
            this.listingsSelected = listingsSelected;
        }

        private void record(PricingDecision decision) {
            decisionsWritten++;
            if (STATUS_PROPOSED.equals(decision.getDecisionStatusCode())) {
                proposedDecisions++;
            } else if (STATUS_SKIPPED.equals(decision.getDecisionStatusCode())) {
                skippedDecisions++;
            } else if (STATUS_FAILED.equals(decision.getDecisionStatusCode())) {
                failedDecisions++;
            }
        }
    }
}
