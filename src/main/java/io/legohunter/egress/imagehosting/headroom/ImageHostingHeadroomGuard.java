package io.legohunter.egress.imagehosting.headroom;

import io.legohunter.egress.imagehosting.ImageHostingRetryTemplate;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.imaging.model.HostedAccountUsage;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Keeps a limited image host account below its item limit (HOMELAB-112).
 *
 * <p>SANDBOX and DEV publish to free Flickr accounts, which hold 1,000 items; above that Flickr deletes the
 * oldest. The database is the system of record, and putting deleted photos back only makes Flickr delete others,
 * so the account must never be pushed over its limit in the first place. Before new photos are published this
 * guard reads the account's count and limit, and refuses once the count would pass the limit less a reserve.
 *
 * <p>An account with no limit (production's Pro account) is always allowed. If the account cannot be read,
 * publishing is allowed: a provider hiccup must not stop production, and one missed check on a free account
 * costs at most one batch of headroom.
 *
 * <p>The reading is kept for a short time and uploads allowed meanwhile are added to it, so a run that publishes
 * many items neither asks the provider for every item nor overshoots between readings.
 */
@Slf4j
@Service
public class ImageHostingHeadroomGuard {
    private final Optional<ImageHostingService> imageHostingService;
    private final ImageHostingSyncProperties properties;
    private final ImageHostingRetryTemplate retryTemplate;
    private final Clock clock;

    private HostedAccountUsage cachedUsage;
    private String cachedFailure;
    private Instant cachedAt;
    private int allowedSinceRead;

    @Autowired
    public ImageHostingHeadroomGuard(
            Optional<ImageHostingService> imageHostingService,
            ImageHostingSyncProperties properties,
            ImageHostingRetryTemplate retryTemplate
    ) {
        this(imageHostingService, properties, retryTemplate, Clock.systemUTC());
    }

    ImageHostingHeadroomGuard(
            Optional<ImageHostingService> imageHostingService,
            ImageHostingSyncProperties properties,
            ImageHostingRetryTemplate retryTemplate,
            Clock clock
    ) {
        this.imageHostingService = imageHostingService;
        this.properties = properties;
        this.retryTemplate = retryTemplate;
        this.clock = clock;
    }

    /**
     * Asks to publish {@code newPhotos} new photos. When allowed, they are counted against the account until the
     * next reading.
     */
    public synchronized HeadroomDecision reserve(int newPhotos) {
        ImageHostingSyncProperties.Headroom settings = properties.getSync().getHeadroom();
        if (!settings.isEnabled() || newPhotos <= 0) {
            return new HeadroomDecision(true, null, null);
        }
        AccountHeadroom headroom = read();
        // Unreadable, unlimited, or limited without a reported count: there is nothing to measure against.
        if (!headroom.known() || !headroom.limited() || headroom.available() == null) {
            return new HeadroomDecision(true, null, headroom);
        }
        if (headroom.available() < newPhotos) {
            return new HeadroomDecision(
                    false,
                    "The image host account holds %s of %s items; publishing stops at %s to keep %s free, and %s new photo(s) would pass it. Remove photos no item owns, or photos of items no longer needed, to free space."
                            .formatted(headroom.itemCount(), headroom.itemLimit(), headroom.publishCeiling(),
                                    headroom.reserveItems(), newPhotos),
                    headroom
            );
        }
        allowedSinceRead += newPhotos;
        return new HeadroomDecision(true, null, read());
    }

    /** The account's current standing, for logs and the status endpoint. Does not reserve anything. */
    public synchronized AccountHeadroom current() {
        return read();
    }

    private AccountHeadroom read() {
        ImageHostingSyncProperties.Headroom settings = properties.getSync().getHeadroom();
        int reserve = Math.max(0, settings.getReserveItems());
        Instant now = clock.instant();
        if (cachedAt == null || Duration.between(cachedAt, now).getSeconds() >= Math.max(0, settings.getCacheSeconds())) {
            refresh(now);
        }
        if (cachedUsage == null) {
            return new AccountHeadroom(false, null, null, false, reserve, null, null, cachedFailure);
        }
        if (!cachedUsage.limited() || cachedUsage.itemCount() == null) {
            return new AccountHeadroom(true, cachedUsage.itemCount(), cachedUsage.itemLimit(), cachedUsage.limited(),
                    reserve, null, null, cachedUsage.limited() ? "The account has a limit but did not report its item count" : null);
        }
        int count = cachedUsage.itemCount() + allowedSinceRead;
        int ceiling = Math.max(0, cachedUsage.itemLimit() - reserve);
        return new AccountHeadroom(true, count, cachedUsage.itemLimit(), true, reserve, ceiling,
                Math.max(0, ceiling - count), null);
    }

    private void refresh(Instant now) {
        cachedAt = now;
        allowedSinceRead = 0;
        cachedUsage = null;
        cachedFailure = null;
        if (imageHostingService.isEmpty()) {
            cachedFailure = "No image hosting service is configured";
            return;
        }
        try {
            PhotoServiceResponse<HostedAccountUsage> response = retryTemplate.execute(
                    "getAccountUsage",
                    () -> imageHostingService.get().getAccountUsage()
            ).response();
            if (response.isError()) {
                cachedFailure = response.responseMessage();
            } else {
                cachedUsage = response.get();
            }
        } catch (RuntimeException e) {
            cachedFailure = e.getMessage();
        }
        if (cachedUsage == null) {
            log.warn("image_hosting.headroom.unreadable message={} publishingAllowed=true", cachedFailure);
        } else {
            log.info(
                    "image_hosting.headroom.account_usage itemCount={} itemLimit={} limited={} reserveItems={}",
                    cachedUsage.itemCount(),
                    cachedUsage.itemLimit(),
                    cachedUsage.limited(),
                    properties.getSync().getHeadroom().getReserveItems()
            );
        }
    }
}
