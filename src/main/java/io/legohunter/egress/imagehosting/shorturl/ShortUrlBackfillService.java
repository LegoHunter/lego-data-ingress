package io.legohunter.egress.imagehosting.shorturl;

import io.legohunter.data.bricklink.BricklinkLotText;
import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalService;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.ingress.marketplace.bricklink.BricklinkLotDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static io.legohunter.data.enums.ExternalSyncStatus.PENDING;

/**
 * Gives every existing Flickr album its calculated {@code flic.kr/s/} short link (HOMELAB-128): albums with no
 * short link, a bit.ly link, or any other value. Dry run unless {@code apply=true}.
 *
 * <p>Only the album row changes here. The new link then reaches the buyer by the usual paths. The album is marked
 * PENDING, because the image-hosting sync only revisits an item for a missing link, a failed or pending row or a
 * changed photo, never for a changed short link. The next scheduled sync then plans the album, finds its Flickr
 * description still carrying the old link and rewrites it, which marks the album SYNCED again. BrickLink
 * reconciliation rebuilds the lot Description from the album row, reports it as drifted and queues its update.
 *
 * <p>For each album the report lists the item's BrickLink listings with the length their Description will have
 * with the new link, so a Description that would pass BrickLink's 255 characters can be shortened first. This is
 * the list to read before the PROD cutover, where the legacy listings move from bit.ly to flic.kr.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortUrlBackfillService {
    private static final Integer FLICKR = ExternalService.Service.FLICKR.getExternalServiceId();
    private static final Integer BRICKLINK = ExternalService.Service.BRICKLINK.getExternalServiceId();

    private final ExternalImageAlbumDao externalImageAlbumDao;
    private final MarketplaceListingDao marketplaceListingDao;
    private final ItemInventoryPhotoDao itemInventoryPhotoDao;
    private final ObjectProvider<BricklinkLotDescription> lotDescription;

    public Result backfill(boolean apply) {
        List<Row> rows = new ArrayList<>();
        int unchanged = 0;
        int noAlbumId = 0;
        List<ExternalImageAlbum> albums = externalImageAlbumDao.findAll().stream()
                .filter(album -> FLICKR.equals(album.getExternalServiceId()))
                .sorted(Comparator.comparing(ExternalImageAlbum::getExternalImageAlbumId))
                .toList();
        for (ExternalImageAlbum album : albums) {
            Optional<String> expected = FlickrShortAlbumLink.of(album.getExternalAlbumId());
            if (expected.isEmpty()) {
                noAlbumId++;
                continue;
            }
            String shortUrl = expected.get();
            if (shortUrl.equals(album.getShortUrl())) {
                unchanged++;
                continue;
            }
            String previous = album.getShortUrl();
            List<Listing> listings = bricklinkListings(album.getItemInventoryId(), shortUrl);
            if (apply) {
                album.setShortUrl(shortUrl);
                album.setSyncStatus(PENDING);
                externalImageAlbumDao.update(album);
            }
            rows.add(new Row(album.getExternalImageAlbumId(), album.getItemInventoryId(), album.getExternalAlbumId(),
                    previous, shortUrl, apply, listings));
        }
        long tooLong = rows.stream().flatMap(row -> row.bricklinkListings().stream()).filter(Listing::tooLong).count();
        log.info("image_hosting.short_url_backfill.completed apply={} albums={} changed={} unchanged={} noAlbumId={} descriptionsTooLong={}",
                apply, albums.size(), rows.size(), unchanged, noAlbumId, tooLong);
        return new Result(apply, albums.size(), rows.size(), unchanged, noAlbumId, tooLong, List.copyOf(rows));
    }

    /** The item's BrickLink listings that have a lot, with the length of their Description once it uses {@code link}. */
    private List<Listing> bricklinkListings(Integer itemInventoryId, String link) {
        BricklinkLotDescription description = lotDescription.getIfAvailable();
        if (itemInventoryId == null || description == null) {
            return List.of();
        }
        boolean anyCaption = !itemInventoryPhotoDao.findProcessedCaptionsByItemInventoryId(itemInventoryId).isEmpty();
        int photoCount = itemInventoryPhotoDao.countProcessedByItemInventoryId(itemInventoryId);
        boolean extendedDescriptionSync = description.writesExtendedDescription();
        return marketplaceListingDao.findByItemInventoryId(itemInventoryId).stream()
                .filter(listing -> BRICKLINK.equals(listing.getListingExternalServiceId()))
                .filter(listing -> listing.getExternalListingId() != null)
                .sorted(Comparator.comparing(MarketplaceListing::getMarketplaceListingId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(listing -> {
                    BricklinkLotText.Description text = BricklinkLotText.description(
                            BricklinkLotText.shortText(listing.getDescription(), listing.getTitle()),
                            link, photoCount, anyCaption, extendedDescriptionSync);
                    return new Listing(listing.getMarketplaceListingId(), listing.getExternalListingId(),
                            listing.getListingStatusCode(), text.present() ? text.length() : 0, text.present() && text.tooLong());
                })
                .toList();
    }

    public record Result(boolean applied, int flickrAlbums, int changed, int unchanged, int withoutAlbumId,
                         long bricklinkDescriptionsTooLong, List<Row> rows) {
    }

    /** One album whose short link changes; {@code applied} tells whether the row was updated. */
    public record Row(Long externalImageAlbumId, Integer itemInventoryId, String externalAlbumId, String currentShortUrl,
                      String newShortUrl, boolean applied, List<Listing> bricklinkListings) {
    }

    /** A BrickLink listing of the album's item, and its Description length with the new link (BrickLink counting). */
    public record Listing(Integer marketplaceListingId, String bricklinkInventoryId, String listingStatusCode,
                          int newDescriptionLength, boolean tooLong) {
    }
}
