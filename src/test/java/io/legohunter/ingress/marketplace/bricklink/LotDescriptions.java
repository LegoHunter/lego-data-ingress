package io.legohunter.ingress.marketplace.bricklink;

import io.legohunter.data.dao.BricklinkLotTextSource;
import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;

import static org.mockito.Mockito.mock;

/** {@link BricklinkLotDescription}s for tests. */
final class LotDescriptions {
    private LotDescriptions() {
    }

    /**
     * Items with no album and no captioned photo, in an environment that does not write the Extended
     * Description: the lot Description is the listing's description or title as a sentence.
     */
    static BricklinkLotDescription textOnly() {
        return new BricklinkLotDescription(
                new BricklinkLotTextSource(mock(ExternalImageAlbumDao.class), mock(ItemInventoryPhotoDao.class)),
                new BricklinkExtendedDescriptionSyncProperties(),
                new BricklinkMarketplaceSyncProperties());
    }
}
