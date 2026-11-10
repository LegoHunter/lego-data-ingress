package io.legohunter.ingress.pricing;

import io.legohunter.data.dao.CanonicalItemDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.WantListItemDao;
import io.legohunter.data.dto.CanonicalItem;
import io.legohunter.data.dto.WantListItem;
import io.legohunter.data.dto.WantedCatalogItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * Keeps this environment's rows in the shared {@code marketplace_pricing.want_list_item} (HOMELAB-32): one row per
 * catalog item, condition and sub-condition it is pricing. The shared BrickLink crawler schedules from the union of
 * every environment's want list. This service only ever writes rows carrying its own environment code.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.pricing.want-list", name = "enabled", havingValue = "true")
public class BricklinkWantListSyncService {
    private static final Set<String> CANONICAL_CONDITIONS = Set.of("N", "U");
    private static final Set<String> CANONICAL_COMPLETENESS = Set.of("S", "C", "B", "X");

    private final BricklinkWantListSyncProperties properties;
    private final MarketplaceListingDao marketplaceListingDao;
    private final CanonicalItemDao canonicalItemDao;
    private final WantListItemDao wantListItemDao;

    public BricklinkWantListSyncResult runOnce() {
        long start = System.currentTimeMillis();
        String environmentCode = properties.effectiveEnvironmentCode();
        ZonedDateTime runAt = now();

        List<WantedCatalogItem> wanted = marketplaceListingDao.findWantedCatalogItemsByListingExternalServiceIdAndListingStatusCodes(
                properties.getBricklinkExternalServiceId(),
                properties.effectivePriceableListingStatusCodes()
        );

        int confirmed = 0;
        int skipped = 0;
        int[] added = {0};
        for (WantedCatalogItem item : wanted) {
            if (!CANONICAL_CONDITIONS.contains(item.getConditionCode()) || !CANONICAL_COMPLETENESS.contains(item.getCompletenessCode())) {
                skipped++;
                log.warn("bricklink.pricing.want_list.skipped itemType={} itemNumber={} condition={} completeness={}",
                        item.getItemTypeCode(), item.getItemNumber(), item.getConditionCode(), item.getCompletenessCode());
                continue;
            }
            CanonicalItem canonicalItem = canonicalItem(item, added);
            wantListItemDao.upsert(WantListItem.builder()
                    .environmentCode(environmentCode)
                    .canonicalItemId(canonicalItem.getCanonicalItemId())
                    .conditionCode(item.getConditionCode())
                    .completenessCode(item.getCompletenessCode())
                    .firstWantedAt(runAt)
                    .lastConfirmedAt(runAt)
                    .build());
            confirmed++;
        }

        int pruned = wantListItemDao.deleteByEnvironmentCodeAndLastConfirmedAtBefore(
                environmentCode, runAt.minus(properties.effectivePruneAfter()));

        return new BricklinkWantListSyncResult(environmentCode, confirmed, added[0], skipped, pruned,
                System.currentTimeMillis() - start);
    }

    /** The shared canonical item for a catalog item, created when this environment is the first to want it. */
    private CanonicalItem canonicalItem(WantedCatalogItem item, int[] added) {
        return canonicalItemDao.findByItemTypeCodeAndItemNumber(item.getItemTypeCode(), item.getItemNumber())
                .orElseGet(() -> {
                    try {
                        CanonicalItem created = canonicalItemDao.insert(CanonicalItem.builder()
                                .itemTypeCode(item.getItemTypeCode())
                                .itemNumber(item.getItemNumber())
                                .itemName(item.getItemName())
                                .build());
                        added[0]++;
                        return created;
                    } catch (DuplicateKeyException e) {
                        // Another environment created it between the lookup and the insert.
                        return canonicalItemDao.findByItemTypeCodeAndItemNumber(item.getItemTypeCode(), item.getItemNumber())
                                .orElseThrow(() -> e);
                    }
                });
    }

    ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }
}
