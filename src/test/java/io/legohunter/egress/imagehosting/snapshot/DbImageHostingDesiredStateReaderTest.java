package io.legohunter.egress.imagehosting.snapshot;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ExternalImageAlbumImageDao;
import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dao.ItemInventoryExternalCatalogItemDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventoryExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.enums.ExternalSyncStatus;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.egress.imagehosting.description.GeneratedDescriptionComposer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DbImageHostingDesiredStateReaderTest {
    private static final int FLICKR_SERVICE_ID = 10;

    @Mock
    private ItemInventoryDao itemInventoryDao;

    @Mock
    private ItemInventoryPhotoDao itemInventoryPhotoDao;

    @Mock
    private ExternalCatalogItemDao externalCatalogItemDao;

    @Mock
    private ItemInventoryExternalCatalogItemDao itemInventoryExternalCatalogItemDao;

    @Mock
    private ExternalImageDao externalImageDao;

    @Mock
    private ExternalImageAlbumDao externalImageAlbumDao;

    @Mock
    private ExternalImageAlbumImageDao externalImageAlbumImageDao;

    private DbImageHostingDesiredStateReader reader;

    @BeforeEach
    void setUp() {
        reader = new DbImageHostingDesiredStateReader(
                itemInventoryDao,
                itemInventoryPhotoDao,
                externalCatalogItemDao,
                itemInventoryExternalCatalogItemDao,
                externalImageDao,
                externalImageAlbumDao,
                externalImageAlbumImageDao,
                properties(),
                new GeneratedDescriptionComposer(
                        itemInventoryDao,
                        itemInventoryPhotoDao,
                        externalCatalogItemDao,
                        itemInventoryExternalCatalogItemDao,
                        externalImageAlbumDao,
                        properties()
                )
        );
    }

    @Test
    void read_buildsCompleteDbDesiredStateSnapshot() {
        ItemInventory inventory = inventory("inventory-uuid", "Inventory album");
        ItemInventoryPhoto primaryPhoto = photo(11, "front.jpg", true);
        ItemInventoryPhoto detailPhoto = photo(12, "detail.jpg", false);
        ExternalCatalogItem externalCatalogItem = externalCatalogItem(501, "4558-1", "Metroliner");
        ExternalImageAlbum album = album();
        ExternalImage primaryImage = externalImage(201L, 11, "flickr-photo-11");
        ExternalImage detailImage = externalImage(202L, 12, "flickr-photo-12");
        ExternalImageAlbumImage primaryMembership = membership(201L, 1, true);
        ExternalImageAlbumImage detailMembership = membership(202L, 2, false);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(detailPhoto, primaryPhoto));
        when(itemInventoryExternalCatalogItemDao.findByItemInventoryId(100)).thenReturn(Set.of(ItemInventoryExternalCatalogItem.builder()
                .externalCatalogItemId(501)
                .itemInventoryId(100)
                .build()));
        when(externalCatalogItemDao.findByExternalCatalogItemId(501)).thenReturn(Optional.of(externalCatalogItem));
        when(externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(FLICKR_SERVICE_ID, 100))
                .thenReturn(Optional.of(album));
        when(externalImageAlbumImageDao.findByExternalImageAlbumId(301L))
                .thenReturn(Set.of(detailMembership, primaryMembership));
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.of(primaryImage));
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 12))
                .thenReturn(Optional.of(detailImage));

        ImageHostingDesiredStateSnapshot snapshot = reader.read(ImageHostingDesiredStateRequest.builder()
                .itemInventoryId(100)
                .build());

        assertThat(snapshot.getProvider()).isEqualTo("flickr");
        assertThat(snapshot.getExternalServiceId()).isEqualTo(FLICKR_SERVICE_ID);
        assertThat(snapshot.getItemInventoryId()).isEqualTo(100);
        assertThat(snapshot.getExternalCatalogItem()).isEqualTo(externalCatalogItem);
        assertThat(snapshot.getAlbum())
                .extracting(
                        DesiredImageHostingAlbum::getDesiredTitle,
                        DesiredImageHostingAlbum::getDesiredDescription,
                        DesiredImageHostingAlbum::getExternalImageAlbumId,
                        DesiredImageHostingAlbum::getExternalAlbumId
                )
                .containsExactly(
                        "4558-1 - Metroliner",
                        """
                                4558-1 - Metroliner.

                                Caption 11 Caption 12

                                Photos: https://flic.kr/s/a100

                                Item reference: inventory-uuid""",
                        301L,
                        "flickr-album-100"
                );
        assertThat(snapshot.getPhotos())
                .extracting(DesiredImageHostingPhoto::getItemInventoryPhotoId)
                .containsExactly(11, 12);
        assertThat(snapshot.primaryPhotoOptional())
                .isPresent()
                .get()
                .extracting(DesiredImageHostingPhoto::getExternalServiceImageId)
                .isEqualTo("flickr-photo-11");
        assertThat(snapshot.getPhotos().getFirst())
                .extracting(
                        DesiredImageHostingPhoto::getExternalImageId,
                        DesiredImageHostingPhoto::getExternalServiceImageId,
                        DesiredImageHostingPhoto::hasS3Object
                )
                .containsExactly(201L, "flickr-photo-11", true);
        assertThat(snapshot.getPhotos().getFirst().getAlbumMembership())
                .extracting(
                        ExternalImageAlbumImage::getExternalImageAlbumId,
                        ExternalImageAlbumImage::getExternalImageId,
                        ExternalImageAlbumImage::getSortOrder,
                        ExternalImageAlbumImage::getPrimary
                )
                .containsExactly(301L, 201L, 1, true);
    }

    @Test
    void read_buildsSnapshotWhenExternalRowsAreMissing() {
        ItemInventory inventory = inventory("inventory-uuid", null);
        ItemInventoryPhoto photo = photo(11, "front.jpg", false);
        photo.setS3Bucket(null);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(itemInventoryExternalCatalogItemDao.findByItemInventoryId(100)).thenReturn(Set.of());
        when(externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(FLICKR_SERVICE_ID, 100))
                .thenReturn(Optional.empty());
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.empty());

        ImageHostingDesiredStateSnapshot snapshot = reader.read(ImageHostingDesiredStateRequest.builder()
                .itemInventoryId(100)
                .build());

        assertThat(snapshot.externalCatalogItemOptional()).isEmpty();
        assertThat(snapshot.getAlbum())
                .extracting(
                        DesiredImageHostingAlbum::getDesiredTitle,
                        DesiredImageHostingAlbum::getDesiredDescription,
                        DesiredImageHostingAlbum::getExternalAlbum
                )
                .containsExactly("Inventory inventory-uuid", """
                        Inventory inventory-uuid.

                        Caption 11""", null);
        assertThat(snapshot.hasPhotos()).isTrue();
        assertThat(snapshot.primaryPhotoOptional()).isEmpty();
        assertThat(snapshot.getPhotos()).hasSize(1);
        assertThat(snapshot.getPhotos().getFirst().externalImageOptional()).isEmpty();
        assertThat(snapshot.getPhotos().getFirst().albumMembershipOptional()).isEmpty();
        assertThat(snapshot.getPhotos().getFirst().hasS3Object()).isFalse();
    }

    @Test
    void read_resolvesRequestedProviderAndExternalServiceId() {
        ItemInventory inventory = inventory("inventory-uuid", "Inventory album");

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of());
        when(itemInventoryExternalCatalogItemDao.findByItemInventoryId(100)).thenReturn(Set.of());
        when(externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(44, 100))
                .thenReturn(Optional.empty());

        ImageHostingDesiredStateSnapshot snapshot = reader.read(ImageHostingDesiredStateRequest.builder()
                .itemInventoryId(100)
                .provider("alternate")
                .externalServiceId(44)
                .build());

        assertThat(snapshot.getProvider()).isEqualTo("alternate");
        assertThat(snapshot.getExternalServiceId()).isEqualTo(44);
        assertThat(snapshot.hasPhotos()).isFalse();
        verify(externalImageAlbumDao).findByExternalServiceIdAndItemInventoryId(44, 100);
    }

    @Test
    void read_rejectsMissingRequestDataAndUnknownInventory() {
        assertThatThrownBy(() -> reader.read(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itemInventoryId is required");

        assertThatThrownBy(() -> reader.read(ImageHostingDesiredStateRequest.builder().build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itemInventoryId is required");

        when(itemInventoryDao.findByItemInventoryId(404)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reader.read(ImageHostingDesiredStateRequest.builder()
                .itemInventoryId(404)
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No item inventory found for id [404]");
    }

    private static ImageHostingSyncProperties properties() {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.setDefaultProvider("flickr");
        properties.getSync().setExternalServiceId(FLICKR_SERVICE_ID);

        ImageHostingSyncProperties.Provider flickr = new ImageHostingSyncProperties.Provider();
        flickr.setExternalServiceId(FLICKR_SERVICE_ID);
        flickr.setDisplayName("Flickr");
        flickr.setMetricsTag("flickr");
        properties.getProviders().put("flickr", flickr);

        ImageHostingSyncProperties.Provider alternate = new ImageHostingSyncProperties.Provider();
        alternate.setExternalServiceId(44);
        alternate.setDisplayName("Alternate");
        alternate.setMetricsTag("alternate");
        properties.getProviders().put("alternate", alternate);

        return properties;
    }

    private static ItemInventory inventory(String uuid, String description) {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(100);
        inventory.setUuid(uuid);
        inventory.setDescription(description);
        return inventory;
    }

    private static ItemInventoryPhoto photo(Integer itemInventoryPhotoId, String fileName, boolean primary) {
        return ItemInventoryPhoto.builder()
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .itemInventoryId(100)
                .s3Bucket("photos")
                .s3Key("100/" + fileName)
                .md5("md5-" + itemInventoryPhotoId)
                .metadataHash("metadata-" + itemInventoryPhotoId)
                .fileName(fileName)
                .primary(primary)
                .caption("Caption " + itemInventoryPhotoId)
                .build();
    }

    private static ExternalCatalogItem externalCatalogItem(Integer externalCatalogItemId, String externalNumber, String name) {
        ExternalCatalogItem externalCatalogItem = new ExternalCatalogItem();
        externalCatalogItem.setExternalCatalogItemId(externalCatalogItemId);
        externalCatalogItem.setExternalServiceId(2);
        externalCatalogItem.setExternalItemKey(externalNumber);
        externalCatalogItem.setItemName(name);
        return externalCatalogItem;
    }

    private static ExternalImageAlbum album() {
        return ExternalImageAlbum.builder()
                .externalImageAlbumId(301L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryId(100)
                .externalAlbumId("flickr-album-100")
                .title("Existing album title")
                .albumUrl("https://flickr.example/albums/100")
                .shortUrl("https://flic.kr/s/a100")
                .syncStatus(ExternalSyncStatus.SYNCED)
                .build();
    }

    private static ExternalImage externalImage(Long externalImageId, Integer itemInventoryPhotoId, String externalServiceImageId) {
        return ExternalImage.builder()
                .externalImageId(externalImageId)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .externalServiceImageId(externalServiceImageId)
                .title("Caption " + itemInventoryPhotoId)
                .md5AtUpload("md5-" + itemInventoryPhotoId)
                .metadataHashAtSync("metadata-" + itemInventoryPhotoId)
                .syncStatus(ExternalSyncStatus.SYNCED)
                .build();
    }

    private static ExternalImageAlbumImage membership(Long externalImageId, Integer sortOrder, boolean primary) {
        return ExternalImageAlbumImage.builder()
                .externalImageAlbumId(301L)
                .externalImageId(externalImageId)
                .sortOrder(sortOrder)
                .primary(primary)
                .build();
    }
}
