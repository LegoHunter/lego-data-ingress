package io.legohunter.ingress.marketplace.bricklink;

import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.ingress.marketplace.MarketplaceSyncCodes;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * The life cycle of a sync request sent through the BrickLink web gateway, shared by the lot image sync
 * (HOMELAB-92) and the Extended Description sync (HOMELAB-82): claim release, success, block, retry and
 * the deferral that gives the attempt back when the gateway or BrickLink cannot work.
 *
 * @param logPrefix for example {@code bricklink.lot_image}; the event names follow it
 */
@Slf4j
record BricklinkGatewaySyncRequests(MarketplaceListingSyncRequestDao syncRequestDao,
                                    BricklinkMarketplaceSyncProperties syncProperties,
                                    String type,
                                    String logPrefix) {
    static final String STATUS_PENDING = BricklinkMarketplaceSyncService.STATUS_PENDING;
    static final String STATUS_CLAIMED = BricklinkMarketplaceSyncService.STATUS_CLAIMED;

    /** A request left CLAIMED by a worker that died (pod killed mid-run) goes back to PENDING, or FAILED when out of attempts. */
    void releaseStaleClaims(ZonedDateTime now) {
        ZonedDateTime cutoff = now.minus(syncProperties.effectiveStaleClaimTimeout());
        for (MarketplaceListingSyncRequest request : syncRequestDao.findBySyncRequestStatusCode(STATUS_CLAIMED)) {
            if (!type.equals(request.getSyncRequestTypeCode()) || request.getClaimedAt() == null || request.getClaimedAt().isAfter(cutoff)) {
                continue;
            }
            log.warn("{}.stale_claim_released marketplaceListingSyncRequestId={} claimedAt={}",
                    logPrefix, request.getMarketplaceListingSyncRequestId(), request.getClaimedAt());
            markRetryOrFailed(request, "Released a stale claim from " + request.getClaimedAt() + ": the worker stopped before finishing", now);
        }
    }

    void markSucceeded(MarketplaceListingSyncRequest request, Inventory remote, ZonedDateTime now) {
        boolean stockRoom = Boolean.TRUE.equals(remote.getIs_stock_room());
        request.setSyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_SUCCEEDED);
        request.setLastErrorMessage(null);
        request.setClaimedAt(null);
        request.setCompletedAt(now);
        request.setRemoteVisibilityScopeCode(stockRoom ? MarketplaceSyncCodes.REMOTE_SCOPE_STOCKROOM : MarketplaceSyncCodes.REMOTE_SCOPE_PUBLIC);
        request.setRemoteVisibilityContainerId(stockRoom ? remote.getStock_room_id() : null);
        request.setRemoteIsPubliclyAvailable(!stockRoom);
        syncRequestDao.update(request);
    }

    void markBlocked(MarketplaceListingSyncRequest request, String statusCode, String message, ZonedDateTime now) {
        request.setSyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_BLOCKED);
        request.setLastErrorMessage(shortMessage(statusCode + ": " + message));
        request.setClaimedAt(null);
        request.setCompletedAt(now);
        syncRequestDao.update(request);
        log.warn("{}.blocked marketplaceListingSyncRequestId={} marketplaceListingId={} statusCode={} message={}",
                logPrefix, request.getMarketplaceListingSyncRequestId(), request.getMarketplaceListingId(), statusCode, message);
    }

    void markRetryOrFailed(MarketplaceListingSyncRequest request, String message, ZonedDateTime now) {
        request.setClaimedAt(null);
        request.setLastErrorMessage(shortMessage(message));
        boolean exhausted = request.getAttemptCount() != null && request.getMaxAttempts() != null
                && request.getAttemptCount() >= request.getMaxAttempts();
        if (exhausted) {
            request.setSyncRequestStatusCode(BricklinkMarketplaceSyncService.STATUS_FAILED);
            request.setCompletedAt(now);
        } else {
            request.setSyncRequestStatusCode(STATUS_PENDING);
            request.setNextAttemptAt(now.plus(syncProperties.effectiveRetryBackoff()));
        }
        syncRequestDao.update(request);
    }

    /** Back to PENDING, due after {@code delay}, with the attempt its claim took given back. */
    void defer(MarketplaceListingSyncRequest request, String message, Duration delay, ZonedDateTime now) {
        int attempts = request.getAttemptCount() == null ? 0 : request.getAttemptCount();
        request.setAttemptCount(Math.max(0, attempts - 1));
        request.setClaimedAt(null);
        request.setLastErrorMessage(shortMessage("Not sent, attempt not counted: " + message));
        request.setSyncRequestStatusCode(STATUS_PENDING);
        request.setNextAttemptAt(now.plus(delay));
        syncRequestDao.update(request);
        log.warn("{}.deferred marketplaceListingSyncRequestId={} marketplaceListingId={} retryAfter={} message={}",
                logPrefix, request.getMarketplaceListingSyncRequestId(), request.getMarketplaceListingId(), delay, message);
    }

    static String shortMessage(String message) {
        return message == null || message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
