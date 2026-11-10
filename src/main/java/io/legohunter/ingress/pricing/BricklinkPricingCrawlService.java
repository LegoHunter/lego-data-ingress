package io.legohunter.ingress.pricing;

import com.bricklink.api.ajax.BricklinkAjaxClient;
import com.bricklink.api.ajax.exception.BricklinkAjaxClientException;
import com.bricklink.api.ajax.model.v1.Item;
import com.bricklink.api.ajax.model.v1.ItemForSale;
import com.bricklink.api.ajax.support.CatalogItemsForSaleResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.data.dao.CanonicalItemDao;
import io.legohunter.data.dao.CrawlAttemptDao;
import io.legohunter.data.dao.CrawlTargetDao;
import io.legohunter.data.dao.MarketListingDao;
import io.legohunter.data.dao.MarketSnapshotDao;
import io.legohunter.data.dao.MarketplaceItemDao;
import io.legohunter.data.dto.CanonicalItem;
import io.legohunter.data.dto.CrawlAttempt;
import io.legohunter.data.dto.CrawlTarget;
import io.legohunter.data.dto.MarketListing;
import io.legohunter.data.dto.MarketSnapshot;
import io.legohunter.data.dto.MarketplaceItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shared BrickLink market crawler (HOMELAB-32). One instance crawls for every environment and writes to the
 * shared {@code marketplace_pricing} schema; every other deployment keeps {@code lego.bricklink.pricing.crawl.enabled}
 * off.
 *
 * <p>Each run:
 * <ol>
 *   <li>keeps the queue in step with the combined want list: a target for every wanted item + New/Used pair (new ones
 *       spread over the schedule window), DISABLED when nobody wants it, due again when it is wanted again;</li>
 *   <li>outside the blackout window, claims due targets and fetches each once. One fetch returns every sub-condition;
 *       pricing filters the lots afterwards. A success adds a market snapshot (the price history) and moves the
 *       target a full cadence ahead; a failure is logged in crawl_attempt and retried after the backoff, or after a
 *       full cadence once {@code max-attempts} failures have run together.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.pricing.crawl", name = "enabled", havingValue = "true")
public class BricklinkPricingCrawlService {
    static final String MARKETPLACE_CODE = "BRICKLINK";
    static final String MATCH_METHOD_CATALOG_ID = "CATALOG_ID";
    static final String LISTING_FORMAT_FIXED_PRICE = "FIXED_PRICE";
    static final BigDecimal CATALOG_MATCH_CONFIDENCE = new BigDecimal("1.0000");

    static final String OUTCOME_RUNNING = "RUNNING";
    static final String OUTCOME_SUCCEEDED = "SUCCEEDED";
    static final String OUTCOME_FAILED_HTTP = "FAILED_HTTP";
    static final String OUTCOME_FAILED_PARSE = "FAILED_PARSE";
    static final String OUTCOME_FAILED_RESOLVE_NO_MATCH = "FAILED_RESOLVE_NO_MATCH";
    static final String OUTCOME_FAILED_RESOLVE_AMBIGUOUS = "FAILED_RESOLVE_AMBIGUOUS";
    static final String OUTCOME_FAILED_MISSING_ITEM = "FAILED_MISSING_ITEM";

    static final String CATALOG_ITEMS_FOR_SALE_PATH = "/ajax/clone/catalogifs.ajax";

    private static final Set<String> CANONICAL_COMPLETENESS = Set.of("S", "C", "B", "X");
    /** Same price format BrickLink uses for the display price: "US $1,234.56", "EUR 23.00", "HUF 15,000.00". */
    private static final Pattern PRICE_PATTERN = Pattern.compile("^([A-Z]{2,3})\\s?([^\\d\\s]?)\\s*([\\d,]+(?:\\.\\d+)?)$");

    private final BricklinkAjaxClient bricklinkAjaxClient;
    private final BricklinkPricingCrawlProperties properties;
    private final CanonicalItemDao canonicalItemDao;
    private final MarketplaceItemDao marketplaceItemDao;
    private final CrawlTargetDao crawlTargetDao;
    private final CrawlAttemptDao crawlAttemptDao;
    private final MarketSnapshotDao marketSnapshotDao;
    private final MarketListingDao marketListingDao;
    private final ObjectMapper objectMapper;

    public BricklinkPricingCrawlResult runOnce() {
        long start = System.currentTimeMillis();
        ZonedDateTime runAt = now();
        CrawlCounters counters = new CrawlCounters();

        scheduleTargets(runAt, counters);

        if (isInBlackout(runAt)) {
            String outcome = counters.scheduledAnything() ? "BLACKOUT" : "NO_WORK";
            return result(outcome, counters, start);
        }

        List<CrawlTarget> due = crawlTargetDao.findDue(MARKETPLACE_CODE, runAt, properties.effectiveWorkerBatchSize());
        for (CrawlTarget target : due) {
            ZonedDateTime claimedAt = now();
            if (!crawlTargetDao.claim(target.getCrawlTargetId(), properties.effectiveClaimedBy(), claimedAt,
                    claimedAt.plus(properties.effectiveClaimStaleAfter()))) {
                continue;
            }
            counters.targetsClaimed++;
            crawl(crawlTargetDao.findByCrawlTargetId(target.getCrawlTargetId()).orElse(target), counters);
        }

        return result(outcome(counters), counters, start);
    }

    private void scheduleTargets(ZonedDateTime runAt, CrawlCounters counters) {
        List<CrawlTarget> wanted = crawlTargetDao.findWantedWithoutTarget(MARKETPLACE_CODE);
        for (int index = 0; index < wanted.size(); index++) {
            CrawlTarget pair = wanted.get(index);
            crawlTargetDao.insert(CrawlTarget.builder()
                    .marketplaceCode(MARKETPLACE_CODE)
                    .canonicalItemId(pair.getCanonicalItemId())
                    .queryConditionCode(pair.getQueryConditionCode())
                    .targetStatusCode(CrawlTargetStatus.IDLE)
                    .nextDueAt(spreadDueAt(runAt, index, wanted.size()))
                    .consecutiveFailureCount(0)
                    .build());
            counters.targetsCreated++;
        }

        for (CrawlTarget target : crawlTargetDao.findDisabledWanted(MARKETPLACE_CODE)) {
            // Owner decision: due at max(now, last success + cadence), so coming back never crawls early.
            ZonedDateTime due = target.getLastSuccessAt() == null
                    ? runAt
                    : later(runAt, target.getLastSuccessAt().plus(properties.effectiveCrawlCadence()));
            target.setTargetStatusCode(CrawlTargetStatus.IDLE);
            target.setNextDueAt(adjustForBlackout(due));
            crawlTargetDao.update(target);
            counters.targetsReenabled++;
        }

        counters.targetsDisabled = crawlTargetDao.disableUnwanted(MARKETPLACE_CODE);
    }

    private void crawl(CrawlTarget target, CrawlCounters counters) {
        CrawlAttempt attempt = crawlAttemptDao.insert(CrawlAttempt.builder()
                .crawlTargetId(target.getCrawlTargetId())
                .startedAt(now())
                .outcomeCode(OUTCOME_RUNNING)
                .build());

        Optional<CanonicalItem> item = canonicalItemDao.findByCanonicalItemId(target.getCanonicalItemId());
        if (item.isEmpty()) {
            fail(target, attempt, OUTCOME_FAILED_MISSING_ITEM, "No canonical_item row for the crawl target", counters);
            return;
        }

        MarketplaceItem marketplaceItem = marketplaceItem(item.get());
        Optional<Integer> idItem = resolveIdItem(item.get(), marketplaceItem, target, attempt, counters);
        if (idItem.isEmpty()) {
            return;
        }

        Map<String, Object> requestParameters = new LinkedHashMap<>();
        requestParameters.put("itemid", idItem.get());
        requestParameters.put("cond", target.getQueryConditionCode());
        requestParameters.put("rpp", properties.effectiveResultsPerPage());
        requestParameters.put("iconly", 0);

        CatalogItemsForSaleResult result;
        try {
            result = bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(
                    idItem.get(), target.getQueryConditionCode(), properties.effectiveResultsPerPage());
        } catch (RuntimeException e) {
            // bricklink-ajax returns an empty result when BrickLink has no lots for sale, so an exception is a real
            // failure: maintenance redirect, block, timeout.
            fail(target, attempt, OUTCOME_FAILED_HTTP, e.getMessage(), counters);
            return;
        }

        try {
            MarketSnapshot snapshot = store(target, item.get(), marketplaceItem, requestParameters, result, counters);
            succeed(target, attempt, snapshot);
        } catch (JsonProcessingException e) {
            fail(target, attempt, OUTCOME_FAILED_PARSE, e.getMessage(), counters);
        }
    }

    private MarketplaceItem marketplaceItem(CanonicalItem item) {
        return marketplaceItemDao.findByMarketplaceCodeAndCanonicalItemId(MARKETPLACE_CODE, item.getCanonicalItemId())
                .orElseGet(() -> marketplaceItemDao.insert(MarketplaceItem.builder()
                        .marketplaceCode(MARKETPLACE_CODE)
                        .canonicalItemId(item.getCanonicalItemId())
                        .marketplaceItemKey(item.getItemNumber())
                        .matchMethodCode(MATCH_METHOD_CATALOG_ID)
                        .build()));
    }

    /** BrickLink's internal idItem, looked up once and kept on marketplace_item for every later crawl. */
    private Optional<Integer> resolveIdItem(
            CanonicalItem item,
            MarketplaceItem marketplaceItem,
            CrawlTarget target,
            CrawlAttempt attempt,
            CrawlCounters counters
    ) {
        Optional<Integer> known = parseInteger(marketplaceItem.getMarketplaceUniqueKey());
        if (known.isPresent()) {
            return known;
        }
        try {
            Optional<Item> found = bricklinkAjaxClient.findCatalogItem(item.getItemNumber(), item.getItemTypeCode());
            if (found.isEmpty() || found.get().getIdItem() == null) {
                fail(target, attempt, OUTCOME_FAILED_RESOLVE_NO_MATCH,
                        "No exact BrickLink catalog item match for " + item.getItemTypeCode() + " " + item.getItemNumber(), counters);
                return Optional.empty();
            }
            marketplaceItem.setMarketplaceUniqueKey(String.valueOf(found.get().getIdItem()));
            marketplaceItem.setResolvedAt(now());
            marketplaceItemDao.update(marketplaceItem);
            counters.idItemsResolved++;
            return Optional.of(found.get().getIdItem());
        } catch (BricklinkAjaxClientException e) {
            boolean ambiguous = e.getMessage() != null && e.getMessage().contains("Ambiguous");
            fail(target, attempt, ambiguous ? OUTCOME_FAILED_RESOLVE_AMBIGUOUS : OUTCOME_FAILED_HTTP, e.getMessage(), counters);
            return Optional.empty();
        } catch (RuntimeException e) {
            fail(target, attempt, OUTCOME_FAILED_HTTP, e.getMessage(), counters);
            return Optional.empty();
        }
    }

    private MarketSnapshot store(
            CrawlTarget target,
            CanonicalItem item,
            MarketplaceItem marketplaceItem,
            Map<String, Object> requestParameters,
            CatalogItemsForSaleResult result,
            CrawlCounters counters
    ) throws JsonProcessingException {
        List<ItemForSale> lots = result.getList() == null ? List.of() : result.getList();
        MarketSnapshot snapshot = marketSnapshotDao.insert(MarketSnapshot.builder()
                .crawlTargetId(target.getCrawlTargetId())
                .marketplaceCode(MARKETPLACE_CODE)
                .canonicalItemId(item.getCanonicalItemId())
                .queryConditionCode(target.getQueryConditionCode())
                .marketplaceItemKey(marketplaceItem.getMarketplaceItemKey())
                .marketplaceUniqueKey(marketplaceItem.getMarketplaceUniqueKey())
                .requestUrl(CATALOG_ITEMS_FOR_SALE_PATH)
                .requestParameters(objectMapper.writeValueAsString(requestParameters))
                .rawPayloadHash(sha256(objectMapper.writeValueAsString(result)))
                .listingCount(lots.size())
                .capturedAt(now())
                .build());
        counters.snapshotsWritten++;
        if (lots.isEmpty()) {
            counters.emptySnapshots++;
        }

        for (ItemForSale lot : lots) {
            if (lot.getDisplayCurrencyCode() == null || lot.getSalePrice() == null || lot.getSalePrice() <= 0) {
                log.warn("bricklink.pricing.crawl.unparseable_price marketSnapshotId={} externalListingId={} displayPrice={}",
                        snapshot.getMarketSnapshotId(), lot.getIdInv(), lot.getMDisplaySalePrice());
                continue;
            }
            marketListingDao.insert(marketListing(snapshot, lot));
            counters.listingsWritten++;
        }
        return snapshot;
    }

    MarketListing marketListing(MarketSnapshot snapshot, ItemForSale lot) throws JsonProcessingException {
        String currency = lot.getDisplayCurrencyCode();
        NativePrice nativePrice = nativePrice(lot.getMInvSalePrice()).filter(price -> !price.currencyCode().equals(currency)).orElse(null);
        String completeness = BricklinkPricingCodeNormalizer.completeness(lot.getCodeComplete());
        return MarketListing.builder()
                .marketSnapshotId(snapshot.getMarketSnapshotId())
                .externalListingId(lot.getIdInv() == null ? null : String.valueOf(lot.getIdInv()))
                .sellerKey(lot.getStrSellerUsername())
                .sellerName(lot.getStrStorename())
                .sellerCountryCode(lot.getStrSellerCountryCode())
                .sellerFeedbackScore(lot.getN4SellerFeedbackScore())
                .rawConditionCode(lot.getCodeNew())
                .rawCompletenessCode(lot.getCodeComplete())
                .conditionCode(BricklinkPricingCodeNormalizer.condition(lot.getCodeNew()))
                .completenessCode(completeness != null && CANONICAL_COMPLETENESS.contains(completeness) ? completeness : null)
                .matchConfidence(CATALOG_MATCH_CONFIDENCE)
                .listingFormatCode(LISTING_FORMAT_FIXED_PRICE)
                .quantityAvailable(lot.getN4Qty())
                .itemPrice(BigDecimal.valueOf(lot.getSalePrice()))
                .currencyCode(currency)
                .nativePrice(nativePrice == null ? null : nativePrice.amount())
                .nativeCurrencyCode(nativePrice == null ? null : nativePrice.currencyCode())
                .description(lot.getStrDesc())
                .sourceListingPayload(objectMapper.writeValueAsString(lot))
                .build();
    }

    private void succeed(CrawlTarget target, CrawlAttempt attempt, MarketSnapshot snapshot) {
        ZonedDateTime finishedAt = now();
        attempt.setFinishedAt(finishedAt);
        attempt.setOutcomeCode(OUTCOME_SUCCEEDED);
        attempt.setMarketSnapshotId(snapshot.getMarketSnapshotId());
        crawlAttemptDao.finish(attempt);

        release(target, finishedAt);
        target.setLastSuccessAt(snapshot.getCapturedAt());
        target.setLastMarketSnapshotId(snapshot.getMarketSnapshotId());
        target.setConsecutiveFailureCount(0);
        target.setLastErrorMessage(null);
        target.setNextDueAt(adjustForBlackout(finishedAt.plus(properties.effectiveCrawlCadence())));
        crawlTargetDao.update(target);
    }

    private void fail(CrawlTarget target, CrawlAttempt attempt, String outcomeCode, String message, CrawlCounters counters) {
        ZonedDateTime finishedAt = now();
        attempt.setFinishedAt(finishedAt);
        attempt.setOutcomeCode(outcomeCode);
        attempt.setErrorMessage(message);
        crawlAttemptDao.finish(attempt);

        int failures = (target.getConsecutiveFailureCount() == null ? 0 : target.getConsecutiveFailureCount()) + 1;
        release(target, finishedAt);
        target.setConsecutiveFailureCount(failures);
        target.setLastErrorMessage(message);
        // After max-attempts failures in a row, wait a full cadence instead of hammering an item BrickLink keeps refusing.
        Duration wait = failures >= properties.effectiveMaxAttempts() ? properties.effectiveCrawlCadence() : properties.effectiveRetryBackoff();
        target.setNextDueAt(adjustForBlackout(finishedAt.plus(wait)));
        crawlTargetDao.update(target);

        counters.failedFetches++;
        log.warn("bricklink.pricing.crawl.failed crawlTargetId={} canonicalItemId={} condition={} outcome={} consecutiveFailures={} message={}",
                target.getCrawlTargetId(), target.getCanonicalItemId(), target.getQueryConditionCode(), outcomeCode, failures, message);
    }

    private void release(CrawlTarget target, ZonedDateTime attemptAt) {
        target.setTargetStatusCode(CrawlTargetStatus.IDLE);
        target.setClaimedAt(null);
        target.setClaimExpiresAt(null);
        target.setClaimedBy(null);
        target.setLastAttemptAt(attemptAt);
    }

    private ZonedDateTime spreadDueAt(ZonedDateTime runAt, int index, int count) {
        long spreadMillis = properties.effectiveScheduleSpreadWindow().toMillis();
        long offsetMillis = count <= 1 ? 0 : (spreadMillis * index) / count;
        long jitterMillis = properties.effectiveScheduleJitter().toMillis();
        long jitter = jitterMillis <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterMillis + 1);
        return adjustForBlackout(runAt.plus(Duration.ofMillis(offsetMillis + jitter)));
    }

    ZonedDateTime adjustForBlackout(ZonedDateTime attemptAt) {
        BricklinkPricingCrawlProperties.Blackout blackout = properties.getBlackout();
        if (blackout == null || !blackout.isEnabled() || !isInBlackout(attemptAt)) {
            return attemptAt;
        }
        ZoneId zone = ZoneId.of(blackout.getZoneId());
        ZonedDateTime local = attemptAt.withZoneSameInstant(zone);
        LocalDate nextAllowedDate = local.toLocalDate();
        if (!local.toLocalTime().isBefore(blackout.getEnd())) {
            nextAllowedDate = nextAllowedDate.plusDays(1);
        }
        return ZonedDateTime.of(nextAllowedDate, blackout.getEnd(), zone).withZoneSameInstant(ZoneOffset.UTC);
    }

    boolean isInBlackout(ZonedDateTime at) {
        BricklinkPricingCrawlProperties.Blackout blackout = properties.getBlackout();
        if (blackout == null || !blackout.isEnabled()) {
            return false;
        }
        ZonedDateTime local = at.withZoneSameInstant(ZoneId.of(blackout.getZoneId()));
        if (blackout.isWeekdaysOnly() && isWeekend(local.getDayOfWeek())) {
            return false;
        }
        if (blackout.getStart().isBefore(blackout.getEnd())) {
            return !local.toLocalTime().isBefore(blackout.getStart()) && local.toLocalTime().isBefore(blackout.getEnd());
        }
        return !local.toLocalTime().isBefore(blackout.getStart()) || local.toLocalTime().isBefore(blackout.getEnd());
    }

    private static boolean isWeekend(DayOfWeek dayOfWeek) {
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }

    /** The seller's own-currency price ("EUR 315.00", "CA $412.50"), or empty when it cannot be read. */
    static Optional<NativePrice> nativePrice(String price) {
        if (price == null) {
            return Optional.empty();
        }
        Matcher matcher = PRICE_PATTERN.matcher(price.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String prefix = matcher.group(1);
        String code = prefix.length() == 3 ? prefix : "$".equals(matcher.group(2)) ? prefix + "D" : null;
        if (code == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new NativePrice(new BigDecimal(matcher.group(3).replace(",", "")), code));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    record NativePrice(BigDecimal amount, String currencyCode) {
    }

    private String outcome(CrawlCounters counters) {
        if (counters.failedFetches > 0) {
            return "PARTIAL_SUCCESS";
        }
        if (counters.targetsClaimed > 0) {
            return "SUCCESS";
        }
        return counters.scheduledAnything() ? "SCHEDULED" : "NO_WORK";
    }

    private BricklinkPricingCrawlResult result(String outcome, CrawlCounters counters, long start) {
        long elapsed = System.currentTimeMillis() - start;
        if ("NO_WORK".equals(outcome)) {
            return BricklinkPricingCrawlResult.noWork(elapsed);
        }
        return new BricklinkPricingCrawlResult(
                outcome,
                counters.targetsCreated,
                counters.targetsReenabled,
                counters.targetsDisabled,
                counters.targetsClaimed,
                counters.snapshotsWritten,
                counters.emptySnapshots,
                counters.listingsWritten,
                counters.idItemsResolved,
                counters.failedFetches,
                elapsed
        );
    }

    private static ZonedDateTime later(ZonedDateTime a, ZonedDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static Optional<Integer> parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.valueOf(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    /** Crawl target statuses, as stored in crawl_target.target_status_code. */
    static final class CrawlTargetStatus {
        static final String IDLE = "IDLE";
        static final String DISABLED = "DISABLED";

        private CrawlTargetStatus() {
        }
    }

    private static final class CrawlCounters {
        private int targetsCreated;
        private int targetsReenabled;
        private int targetsDisabled;
        private int targetsClaimed;
        private int snapshotsWritten;
        private int emptySnapshots;
        private int listingsWritten;
        private int idItemsResolved;
        private int failedFetches;

        boolean scheduledAnything() {
            return targetsCreated > 0 || targetsReenabled > 0 || targetsDisabled > 0;
        }
    }
}
