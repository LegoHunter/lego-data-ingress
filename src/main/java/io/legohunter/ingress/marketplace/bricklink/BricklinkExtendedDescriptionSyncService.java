package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.bricklink.BricklinkLotText;
import io.legohunter.data.dao.BricklinkLotTextSource;
import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dto.BricklinkListingCaption;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.ingress.marketplace.MarketplaceSyncCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps each BrickLink lot's Extended Description equal to the captions of its item's photos, one per
 * line, through the BrickLink web gateway (HOMELAB-82). Every Extended Description write goes through
 * the gateway: the official API has no such field.
 *
 * <p>One run does two things, as the lot image sync does. It queues an {@value #TYPE} request for every
 * listing whose lot does not hold the text its captions call for, found by comparing the SHA-256 of that
 * text with the hash recorded for the text last sent (no BrickLink request). Then it sends the waiting
 * requests, a few per run.
 *
 * <p>Rules:
 * <ul>
 *   <li>No caption and nothing ever sent: nothing to do. A caption removed since the last send clears the
 *       field (an empty text).</li>
 *   <li>Before the gateway is asked, the lot is read through the official API and must pass the same
 *       ownership and stockroom check as every other sync request. The gateway checks the stockroom again.</li>
 *   <li>The text is built again when the request is sent, so captions changed since it was queued are
 *       included.</li>
 *   <li>A gateway that cannot work (signed out, unreachable) or a BrickLink API outage costs no attempt: the
 *       request goes back to PENDING and the run stops sending.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lego.bricklink.extended-description-sync", name = "enabled", havingValue = "true")
public class BricklinkExtendedDescriptionSyncService {
    public static final String TYPE = "EXTENDED_DESCRIPTION_UPDATE";
    static final Set<String> TYPES = Set.of(TYPE);
    static final String SYNC_REASON = "EXTENDED_DESCRIPTION_OUT_OF_DATE";
    static final String CREATED_BY = "BricklinkExtendedDescriptionSync";
    static final String GATEWAY_NOT_CONFIGURED = "GATEWAY_NOT_CONFIGURED";
    /** BrickLink kept only the start of the text: its Extended Description has a length limit (HOMELAB-82). */
    static final String EXTENDED_DESCRIPTION_TRUNCATED = "EXTENDED_DESCRIPTION_TRUNCATED";

    private static final String STATUS_PENDING = BricklinkGatewaySyncRequests.STATUS_PENDING;
    private static final String STATUS_CLAIMED = BricklinkGatewaySyncRequests.STATUS_CLAIMED;

    private final BricklinkExtendedDescriptionSyncProperties properties;
    private final BricklinkMarketplaceSyncProperties syncProperties;
    private final BricklinkWebGatewayProperties gatewayProperties;
    private final MarketplaceListingSyncRequestDao syncRequestDao;
    private final MarketplaceListingDao marketplaceListingDao;
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao;
    private final ItemInventoryDao itemInventoryDao;
    private final BricklinkLotTextSource lotTextSource;
    private final BricklinkRestClient bricklinkRestClient;
    private final BricklinkRemoteInventorySafetyService safetyService;
    private final BricklinkWebGatewayClient gateway;

    public BricklinkExtendedDescriptionSyncResult runOnce() {
        long start = System.currentTimeMillis();
        BricklinkMarketplaceSyncMode mode = properties.effectiveMode(syncProperties.effectiveMode());
        Counters counters = new Counters();

        List<Integer> listingIds = outOfDateListingIds();
        counters.outOfDate = listingIds.size();

        if (mode == BricklinkMarketplaceSyncMode.DRY_RUN) {
            for (Integer listingId : listingIds) {
                dryRun(listingId, counters);
            }
            return counters.result(mode, System.currentTimeMillis() - start);
        }

        if (!gatewayProperties.configured()) {
            // Nothing is queued either: requests that can never be sent would only pile up.
            log.error("bricklink.extended_description.gateway_not_configured outOfDate={} message=The gateway address or caller token is not set", listingIds.size());
            counters.notConfigured = true;
            return counters.result(mode, System.currentTimeMillis() - start);
        }

        requests().releaseStaleClaims(now());
        for (Integer listingId : listingIds) {
            queue(listingId, counters);
        }
        Set<MarketplaceListingSyncRequest> waiting = syncRequestDao.findClaimableByStatusCodeAndSyncRequestTypeCodes(
                STATUS_PENDING, TYPES, now(), properties.effectiveBatchSize());
        for (MarketplaceListingSyncRequest request : waiting) {
            MarketplaceListingSyncRequest claimed = syncRequestDao
                    .claim(request.getMarketplaceListingSyncRequestId(), STATUS_PENDING, STATUS_CLAIMED, now())
                    .orElse(null);
            if (claimed == null) {
                continue;
            }
            counters.claimed++;
            if (process(claimed, counters)) {
                // The gateway or BrickLink cannot work: every further text would end the same way.
                break;
            }
        }
        return counters.result(mode, System.currentTimeMillis() - start);
    }

    /**
     * The listings whose lot does not hold the text their captions call for, in listing order, at most the
     * queue limit. Compared in the local database only.
     */
    List<Integer> outOfDateListingIds() {
        List<BricklinkListingCaption> rows = bricklinkMarketplaceListingDao.findListingCaptionsForExtendedDescription(
                syncProperties.getBricklinkExternalServiceId(),
                syncProperties.effectiveEnvironmentCode(),
                TYPE,
                now().minus(syncProperties.effectiveRetryBackoff()));
        Map<Integer, Listing> listings = new LinkedHashMap<>();
        for (BricklinkListingCaption row : rows) {
            Listing listing = listings.computeIfAbsent(row.getMarketplaceListingId(),
                    id -> new Listing(row.getExtendedDescriptionHash(), new ArrayList<>()));
            if (row.getCaption() != null) {
                listing.captions().add(row.getCaption());
            }
        }
        List<Integer> outOfDate = new ArrayList<>();
        for (Map.Entry<Integer, Listing> entry : listings.entrySet()) {
            if (outOfDate.size() >= properties.effectiveQueueLimit()) {
                break;
            }
            String text = BricklinkLotText.extendedDescription(entry.getValue().captions());
            if (!isCurrent(entry.getValue().recordedHash(), text)) {
                outOfDate.add(entry.getKey());
            }
        }
        return outOfDate;
    }

    /** Whether the lot already holds {@code text}: its hash was recorded, or nothing was ever sent and nothing is wanted. */
    static boolean isCurrent(String recordedHash, String text) {
        if (recordedHash == null) {
            return text == null || text.isEmpty();
        }
        return recordedHash.equals(BricklinkLotText.hash(text));
    }

    private void queue(Integer marketplaceListingId, Counters counters) {
        BricklinkMarketplaceListing bricklinkListing = bricklinkMarketplaceListingDao.findByMarketplaceListingId(marketplaceListingId).orElse(null);
        if (bricklinkListing == null || bricklinkListing.getBricklinkInventoryId() == null) {
            return;
        }
        boolean nonProd = syncProperties.nonProdEnvironment();
        MarketplaceListingSyncRequest request = syncRequestDao.insert(MarketplaceListingSyncRequest.builder()
                .marketplaceListingId(marketplaceListingId)
                .listingExternalServiceId(syncProperties.getBricklinkExternalServiceId())
                .syncRequestTypeCode(TYPE)
                .syncRequestStatusCode(STATUS_PENDING)
                .syncReasonCode(SYNC_REASON)
                .remoteInventoryId(String.valueOf(bricklinkListing.getBricklinkInventoryId()))
                .remoteVisibilityScopeCode(nonProd ? MarketplaceSyncCodes.REMOTE_SCOPE_STOCKROOM : null)
                .remoteVisibilityContainerId(nonProd ? syncProperties.effectiveNonProdStockRoomId() : null)
                .remoteIsPubliclyAvailable(nonProd ? Boolean.FALSE : null)
                .environmentCode(syncProperties.effectiveEnvironmentCode())
                .createdByJobName(CREATED_BY)
                .attemptCount(0)
                .maxAttempts(properties.effectiveMaxAttempts())
                // Whole seconds: the column has no fractions and MySQL rounds half up.
                .nextAttemptAt(now().truncatedTo(ChronoUnit.SECONDS))
                .build());
        counters.queued++;
        log.info("bricklink.extended_description.queued marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={}",
                request.getMarketplaceListingSyncRequestId(), marketplaceListingId, bricklinkListing.getBricklinkInventoryId());
    }

    /** What an APPLY run would do for this listing, with every check but nothing written anywhere. */
    private void dryRun(Integer marketplaceListingId, Counters counters) {
        try {
            Context context = context(marketplaceListingId, null);
            Inventory remote = data(bricklinkRestClient.getInventories(context.lotId()));
            BricklinkRemoteInventorySafetyResult safety = safetyService.verify(
                    syncProperties, context.listing(), context.bricklinkListing(), context.itemInventory(), remote);
            if (!safety.allowed()) {
                counters.blocked++;
                log.warn("bricklink.extended_description.dry_run_blocked marketplaceListingId={} bricklinkInventoryId={} statusCode={}",
                        marketplaceListingId, context.lotId(), safety.statusCode());
                return;
            }
            String text = lotTextSource.extendedDescription(context.itemInventory().getItemInventoryId());
            counters.dryRunVerified++;
            log.info("bricklink.extended_description.dry_run_verified marketplaceListingId={} bricklinkInventoryId={} characters={} lines={}",
                    marketplaceListingId, context.lotId(), text.length(), text.isEmpty() ? 0 : text.split("\n").length);
        } catch (RuntimeException e) {
            counters.failed++;
            log.warn("bricklink.extended_description.dry_run_failed marketplaceListingId={} message={}", marketplaceListingId, e.getMessage());
        }
    }

    /**
     * One claimed request; a failure is recorded on it, never thrown.
     *
     * @return true when the run should stop sending (the gateway or BrickLink cannot work now)
     */
    private boolean process(MarketplaceListingSyncRequest request, Counters counters) {
        try {
            Context context = context(request.getMarketplaceListingId(), request);
            Inventory remote = data(bricklinkRestClient.getInventories(context.lotId()));
            BricklinkRemoteInventorySafetyResult safety = safetyService.verify(
                    syncProperties, context.listing(), context.bricklinkListing(), context.itemInventory(), remote);
            if (!safety.allowed()) {
                counters.blocked++;
                requests().markBlocked(request, safety.statusCode(), safety.message(), now());
                return false;
            }
            String text = lotTextSource.extendedDescription(context.itemInventory().getItemInventoryId());
            if (isCurrent(context.bricklinkListing().getExtendedDescriptionHash(), text)) {
                counters.alreadyCurrent++;
                requests().markSucceeded(request, remote, now());
                return false;
            }

            BricklinkWebGatewayClient.Answer answer = gateway.putExtendedDescription(context.lotId(), text);
            switch (answer.kind()) {
                case STORED -> {
                    bricklinkMarketplaceListingDao.updateExtendedDescription(request.getMarketplaceListingId(), BricklinkLotText.hash(text), now());
                    String truncation = truncation(text, answer.storedText());
                    if (truncation != null) {
                        // Recorded all the same, so the same text is not sent again on every run: the captions must
                        // be shortened, and the next caption change sends the new text.
                        counters.blocked++;
                        requests().markBlocked(request, EXTENDED_DESCRIPTION_TRUNCATED, truncation, now());
                        log.error("bricklink.extended_description.truncated marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} message={}",
                                request.getMarketplaceListingSyncRequestId(), request.getMarketplaceListingId(), context.lotId(), truncation);
                        return false;
                    }
                    requests().markSucceeded(request, remote, now());
                    counters.stored++;
                    log.info("bricklink.extended_description.sent marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} characters={}",
                            request.getMarketplaceListingSyncRequestId(), request.getMarketplaceListingId(), context.lotId(), text.length());
                    return false;
                }
                case CANNOT_WORK -> {
                    counters.deferred++;
                    requests().defer(request, answer.code() + ": " + answer.message(), properties.effectiveDeferralDelay(), now());
                    return true;
                }
                case REFUSED -> {
                    counters.blocked++;
                    requests().markBlocked(request, answer.code(), answer.message(), now());
                    return false;
                }
                default -> {
                    counters.failed++;
                    requests().markRetryOrFailed(request, answer.code() + ": " + answer.message(), now());
                    return false;
                }
            }
        } catch (RuntimeException e) {
            if (BricklinkRestOutageClassifier.mayBeOutage(e)) {
                // The official API is down, so the safety check cannot run. Not this request's fault.
                counters.deferred++;
                requests().defer(request, "BrickLink API unavailable: " + e.getMessage(), properties.effectiveDeferralDelay(), now());
                return true;
            }
            counters.failed++;
            requests().markRetryOrFailed(request, e.getMessage(), now());
            log.warn("bricklink.extended_description.failed marketplaceListingSyncRequestId={} marketplaceListingId={} message={}",
                    request.getMarketplaceListingSyncRequestId(), request.getMarketplaceListingId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Whether BrickLink cut the text short, judged from what the gateway read back. BrickLink keeps the text as
     * HTML ({@code "} as {@code &quot;}, line ends as LF) and may tidy it, so the stored text is unescaped first; only
     * a stored text that is a strict start of the one sent counts.
     *
     * @return a message with both lengths, or null when the text was kept whole (or nothing was read back)
     */
    static String truncation(String sent, String stored) {
        if (stored == null || sent == null || sent.isEmpty()) {
            return null;
        }
        String wanted = sent.replace("\r\n", "\n").strip();
        String kept = HtmlUtils.htmlUnescape(stored).replace("\r\n", "\n").strip();
        if (kept.length() < wanted.length() && wanted.startsWith(kept)) {
            return "BrickLink kept %d of the %d characters sent (%d as BrickLink stores them); shorten the photo captions"
                    .formatted(kept.length(), wanted.length(), stored.strip().length());
        }
        return null;
    }

    private Context context(Integer marketplaceListingId, MarketplaceListingSyncRequest request) {
        MarketplaceListing listing = marketplaceListingDao.findByMarketplaceListingId(marketplaceListingId)
                .orElseThrow(() -> new IllegalStateException("Marketplace listing not found"));
        if (!syncProperties.getBricklinkExternalServiceId().equals(listing.getListingExternalServiceId())) {
            throw new IllegalStateException("Marketplace listing does not belong to configured BrickLink external service");
        }
        BricklinkMarketplaceListing bricklinkListing = bricklinkMarketplaceListingDao.findByMarketplaceListingId(marketplaceListingId)
                .orElseThrow(() -> new IllegalStateException("BrickLink listing mapping not found"));
        ItemInventory itemInventory = itemInventoryDao.findByItemInventoryId(listing.getItemInventoryId())
                .orElseThrow(() -> new IllegalStateException("Item inventory not found"));
        Integer lotId = bricklinkListing.getBricklinkInventoryId();
        if (lotId == null) {
            throw new IllegalStateException("BrickLink inventory id is missing");
        }
        if (request != null && request.getRemoteInventoryId() != null && !request.getRemoteInventoryId().equals(lotId.toString())) {
            throw new IllegalStateException("Sync request remote inventory id does not match BrickLink listing mapping");
        }
        return new Context(listing, bricklinkListing, itemInventory, lotId.longValue());
    }

    private BricklinkGatewaySyncRequests requests() {
        return new BricklinkGatewaySyncRequests(syncRequestDao, syncProperties, TYPE, "bricklink.extended_description");
    }

    private static <T> T data(BricklinkResource<T> resource) {
        return resource == null ? null : resource.getData();
    }

    ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    private record Listing(String recordedHash, List<String> captions) {
    }

    private record Context(MarketplaceListing listing, BricklinkMarketplaceListing bricklinkListing, ItemInventory itemInventory, long lotId) {
    }

    private static final class Counters {
        private int outOfDate;
        private int queued;
        private int claimed;
        private int stored;
        private int alreadyCurrent;
        private int blocked;
        private int failed;
        private int deferred;
        private int dryRunVerified;
        private boolean notConfigured;

        private BricklinkExtendedDescriptionSyncResult result(BricklinkMarketplaceSyncMode mode, long elapsedMillis) {
            String outcome = notConfigured ? GATEWAY_NOT_CONFIGURED
                    : deferred > 0 ? "DEFERRED"
                    : outOfDate == 0 && claimed == 0 ? "NO_WORK"
                    : "COMPLETED";
            return new BricklinkExtendedDescriptionSyncResult(mode, outcome, outOfDate, queued, claimed, stored, alreadyCurrent,
                    blocked, failed, deferred, dryRunVerified, elapsedMillis);
        }
    }
}
