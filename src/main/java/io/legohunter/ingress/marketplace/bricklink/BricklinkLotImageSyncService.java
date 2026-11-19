package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.imaging.scaling.ImageScalingService;
import io.legohunter.ingress.marketplace.MarketplaceSyncCodes;
import io.legohunter.ingress.s3.api.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * Sets each BrickLink lot's custom image to the item's primary photo, through the BrickLink web
 * gateway (HOMELAB-92).
 *
 * <p>One run does two things. It queues a {@value #TYPE} sync request for every listing whose lot
 * does not show a current primary photo, found by comparing state in the local database. Then it
 * sends the waiting requests, a few per run.
 *
 * <p>Rules:
 * <ul>
 *   <li>No primary photo: nothing is sent. There is no "first photo" fallback.</li>
 *   <li>Several primary photos: one is sent and it is reported. A lot keeps its image as long as
 *       that photo is still one of the primaries.</li>
 *   <li>Before the gateway is asked, the lot is read through the official API and must pass the
 *       same ownership and stockroom check as every other sync request. The gateway checks the
 *       stockroom again.</li>
 *   <li>A gateway that cannot work (signed out, unreachable) costs no attempt: the request goes
 *       back to PENDING and the run stops sending.</li>
 * </ul>
 *
 * <p>The request types here are claimed by this service only; {@link BricklinkMarketplaceSyncService}
 * does not know them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lego.bricklink.lot-image-sync", name = "enabled", havingValue = "true")
public class BricklinkLotImageSyncService {
    public static final String TYPE = "LOT_IMAGE_UPDATE";
    static final Set<String> TYPES = Set.of(TYPE);
    static final String SYNC_REASON = "LOT_IMAGE_OUT_OF_DATE";
    static final String CREATED_BY = "BricklinkLotImageSync";
    static final String NO_PRIMARY_PHOTO = "NO_PRIMARY_PHOTO";
    static final String UNSUPPORTED_IMAGE_FORMAT = "UNSUPPORTED_IMAGE_FORMAT";
    static final String GATEWAY_NOT_CONFIGURED = "GATEWAY_NOT_CONFIGURED";
    /** The gateway's code for "the lot does not show the image after the upload". The old image is gone by then. */
    static final String WRITE_NOT_APPLIED = "WRITE_NOT_APPLIED";

    private static final String STATUS_PENDING = BricklinkGatewaySyncRequests.STATUS_PENDING;
    private static final String STATUS_CLAIMED = BricklinkGatewaySyncRequests.STATUS_CLAIMED;

    private final BricklinkLotImageSyncProperties properties;
    private final BricklinkMarketplaceSyncProperties syncProperties;
    private final MarketplaceListingSyncRequestDao syncRequestDao;
    private final MarketplaceListingDao marketplaceListingDao;
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao;
    private final ItemInventoryDao itemInventoryDao;
    private final ItemInventoryPhotoDao itemInventoryPhotoDao;
    private final BricklinkRestClient bricklinkRestClient;
    private final BricklinkRemoteInventorySafetyService safetyService;
    private final S3Service s3Service;
    private final ImageScalingService imageScalingService;
    private final BricklinkWebGatewayClient gateway;
    private final BricklinkWebGatewayProperties gatewayProperties;
    private final BricklinkApiCredentialsRejectedMetrics credentialsRejectedMetrics;

    public BricklinkLotImageSyncResult runOnce() {
        long start = System.currentTimeMillis();
        BricklinkMarketplaceSyncMode mode = properties.effectiveMode(syncProperties.effectiveMode());
        Counters counters = new Counters();

        List<Integer> listingIds = bricklinkMarketplaceListingDao.findMarketplaceListingIdsNeedingLotImage(
                syncProperties.getBricklinkExternalServiceId(),
                syncProperties.effectiveEnvironmentCode(),
                TYPE,
                now().minus(syncProperties.effectiveRetryBackoff()),
                properties.effectiveQueueLimit());
        counters.outOfDate = listingIds.size();

        if (mode == BricklinkMarketplaceSyncMode.DRY_RUN) {
            for (Integer listingId : listingIds) {
                dryRun(listingId, counters);
            }
            return counters.result(mode, System.currentTimeMillis() - start);
        }

        if (!gatewayProperties.configured()) {
            // Nothing is queued either: requests that can never be sent would only pile up.
            log.error("bricklink.lot_image.gateway_not_configured outOfDate={} message=The gateway address or caller token is not set", listingIds.size());
            counters.notConfigured = true;
            return counters.result(mode, System.currentTimeMillis() - start);
        }

        releaseStaleClaims();
        for (Integer listingId : listingIds) {
            queue(listingId, counters);
        }
        Set<MarketplaceListingSyncRequest> requests = syncRequestDao.findClaimableByStatusCodeAndSyncRequestTypeCodes(
                STATUS_PENDING, TYPES, now(), properties.effectiveBatchSize());
        for (MarketplaceListingSyncRequest request : requests) {
            MarketplaceListingSyncRequest claimed = syncRequestDao
                    .claim(request.getMarketplaceListingSyncRequestId(), STATUS_PENDING, STATUS_CLAIMED, now())
                    .orElse(null);
            if (claimed == null) {
                continue;
            }
            counters.claimed++;
            if (process(claimed, counters)) {
                // The gateway cannot work: every further image would end the same way. The rest stay PENDING.
                break;
            }
        }
        return counters.result(mode, System.currentTimeMillis() - start);
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
                // Whole seconds: the column has no fractions and MySQL rounds half up, which would make a
                // request queued in the second half of a second not yet due in the run that queued it.
                .nextAttemptAt(now().truncatedTo(ChronoUnit.SECONDS))
                .build());
        counters.queued++;
        log.info("bricklink.lot_image.queued marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={}",
                request.getMarketplaceListingSyncRequestId(), marketplaceListingId, bricklinkListing.getBricklinkInventoryId());
    }

    /** What an APPLY run would do for this listing, with every check but nothing written anywhere. */
    private void dryRun(Integer marketplaceListingId, Counters counters) {
        try {
            Context context = context(marketplaceListingId, null);
            Inventory remote = data(bricklinkRestClient.getInventories(context.lotId()));
            BricklinkRemoteInventorySafetyResult safety = safetyService.verify(
                    syncProperties, context.listing(), context.bricklinkListing(), context.itemInventory(), remote);
            ItemInventoryPhoto photo = safety.allowed() ? choosePhoto(context, counters) : null;
            if (!safety.allowed() || photo == null) {
                counters.blocked++;
                log.warn("bricklink.lot_image.dry_run_blocked marketplaceListingId={} bricklinkInventoryId={} statusCode={}",
                        marketplaceListingId, context.lotId(), safety.allowed() ? NO_PRIMARY_PHOTO : safety.statusCode());
                return;
            }
            counters.dryRunVerified++;
            log.info("bricklink.lot_image.dry_run_verified marketplaceListingId={} bricklinkInventoryId={} photoMd5={} fileSize={} recordedMd5={}",
                    marketplaceListingId, context.lotId(), photo.getMd5(), photo.getFileSize(), context.bricklinkListing().getLotImageMd5());
        } catch (RuntimeException e) {
            counters.failed++;
            log.warn("bricklink.lot_image.dry_run_failed marketplaceListingId={} message={}", marketplaceListingId, e.getMessage());
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
                markBlocked(request, safety.statusCode(), safety.message());
                return false;
            }
            ItemInventoryPhoto photo = choosePhoto(context, counters);
            if (photo == null) {
                counters.blocked++;
                markBlocked(request, NO_PRIMARY_PHOTO, "The item has no processed primary photo any more");
                return false;
            }
            if (isRecordedPrimary(context)) {
                counters.alreadyCurrent++;
                markSucceeded(request, remote);
                return false;
            }
            byte[] image = read(photo);
            if (image.length > properties.getMaxImageBytes()) {
                image = imageScalingService.scale(image);
            }
            String extension = extension(image);
            if (extension == null) {
                counters.blocked++;
                markBlocked(request, UNSUPPORTED_IMAGE_FORMAT, "The primary photo is neither a JPEG nor a GIF; BrickLink takes no other format");
                return false;
            }

            BricklinkWebGatewayClient.Answer answer = gateway.putLotImage(context.lotId(), "lot-" + context.lotId() + extension, image);
            switch (answer.kind()) {
                case STORED -> {
                    bricklinkMarketplaceListingDao.updateLotImage(request.getMarketplaceListingId(), photo.getMd5(), now());
                    markSucceeded(request, remote);
                    counters.stored++;
                    log.info("bricklink.lot_image.sent marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} photoMd5={} bytes={}",
                            request.getMarketplaceListingSyncRequestId(), request.getMarketplaceListingId(), context.lotId(), photo.getMd5(), image.length);
                    return false;
                }
                case CANNOT_WORK -> {
                    counters.deferred++;
                    defer(request, answer.code() + ": " + answer.message());
                    return true;
                }
                case REFUSED -> {
                    counters.blocked++;
                    markBlocked(request, answer.code(), answer.message());
                    return false;
                }
                default -> {
                    counters.failed++;
                    if (WRITE_NOT_APPLIED.equals(answer.code())) {
                        // A replace deletes the old image first, so the lot now shows none: forget what was recorded.
                        bricklinkMarketplaceListingDao.updateLotImage(request.getMarketplaceListingId(), null, null);
                    }
                    markRetryOrFailed(request, answer.code() + ": " + answer.message());
                    return false;
                }
            }
        } catch (RuntimeException e) {
            credentialsRejectedMetrics.record(BricklinkApiCredentialsRejectedMetrics.Source.LOT_IMAGE_SYNC, e);
            if (BricklinkRestOutageClassifier.mayBeOutage(e)) {
                // The official API is down, so the safety check cannot run. Not this request's fault.
                counters.deferred++;
                defer(request, "BrickLink API unavailable: " + e.getMessage());
                return true;
            }
            counters.failed++;
            markRetryOrFailed(request, e.getMessage());
            log.warn("bricklink.lot_image.failed marketplaceListingSyncRequestId={} marketplaceListingId={} message={}",
                    request.getMarketplaceListingSyncRequestId(), request.getMarketplaceListingId(), e.getMessage(), e);
            return false;
        }
    }

    /** The primary photo to send, or null when the item has none. With several, the oldest row. */
    private ItemInventoryPhoto choosePhoto(Context context, Counters counters) {
        List<ItemInventoryPhoto> primaries = context.primaries();
        if (primaries.isEmpty()) {
            return null;
        }
        if (primaries.size() > 1) {
            counters.multiplePrimaries++;
            log.warn("bricklink.lot_image.multiple_primary_photos marketplaceListingId={} itemInventoryId={} primaryPhotos={} message=One of them is used; tag only one photo as primary",
                    context.listing().getMarketplaceListingId(), context.itemInventory().getItemInventoryId(), primaries.size());
        }
        return primaries.get(0);
    }

    private boolean isRecordedPrimary(Context context) {
        String recorded = context.bricklinkListing().getLotImageMd5();
        return recorded != null && context.primaries().stream().anyMatch(photo -> recorded.equals(photo.getMd5()));
    }

    private byte[] read(ItemInventoryPhoto photo) {
        try (InputStream in = s3Service.getObject(photo.getS3Bucket(), photo.getS3Key())) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("The primary photo could not be read from the object store", e);
        }
    }

    /** ".jpg", ".gif", or null for anything else. Decided from the first bytes, as the gateway does. */
    static String extension(byte[] image) {
        if (image.length >= 3 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8 && (image[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (image.length >= 4 && image[0] == 'G' && image[1] == 'I' && image[2] == 'F' && image[3] == '8') {
            return ".gif";
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
        List<ItemInventoryPhoto> primaries = itemInventoryPhotoDao.findProcessedPrimariesByItemInventoryId(listing.getItemInventoryId());
        return new Context(listing, bricklinkListing, itemInventory, lotId.longValue(), primaries);
    }

    private BricklinkGatewaySyncRequests requests() {
        return new BricklinkGatewaySyncRequests(syncRequestDao, syncProperties, TYPE, "bricklink.lot_image");
    }

    private void releaseStaleClaims() {
        requests().releaseStaleClaims(now());
    }

    private void markSucceeded(MarketplaceListingSyncRequest request, Inventory remote) {
        requests().markSucceeded(request, remote, now());
    }

    private void markBlocked(MarketplaceListingSyncRequest request, String statusCode, String message) {
        requests().markBlocked(request, statusCode, message, now());
    }

    private void markRetryOrFailed(MarketplaceListingSyncRequest request, String message) {
        requests().markRetryOrFailed(request, message, now());
    }

    /** Back to PENDING, due after the deferral delay, with the attempt its claim took given back. */
    private void defer(MarketplaceListingSyncRequest request, String message) {
        requests().defer(request, message, properties.effectiveDeferralDelay(), now());
    }

    private static <T> T data(BricklinkResource<T> resource) {
        return resource == null ? null : resource.getData();
    }

    ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    private record Context(MarketplaceListing listing, BricklinkMarketplaceListing bricklinkListing, ItemInventory itemInventory,
                           long lotId, List<ItemInventoryPhoto> primaries) {
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
        private int multiplePrimaries;
        private boolean notConfigured;

        private BricklinkLotImageSyncResult result(BricklinkMarketplaceSyncMode mode, long elapsedMillis) {
            String outcome = notConfigured ? GATEWAY_NOT_CONFIGURED
                    : deferred > 0 ? "DEFERRED"
                    : outOfDate == 0 && claimed == 0 ? "NO_WORK"
                    : "COMPLETED";
            return new BricklinkLotImageSyncResult(mode, outcome, outOfDate, queued, claimed, stored, alreadyCurrent,
                    blocked, failed, deferred, dryRunVerified, multiplePrimaries, elapsedMillis);
        }
    }
}
