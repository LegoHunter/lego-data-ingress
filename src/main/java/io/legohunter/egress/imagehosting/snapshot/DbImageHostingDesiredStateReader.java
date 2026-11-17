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
import io.legohunter.egress.imagehosting.ImageHostingAlbumIdentity;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.egress.imagehosting.description.GeneratedDescriptionComposer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DbImageHostingDesiredStateReader implements ImageHostingDesiredStateReader {
    private static final int BRICKLINK_SERVICE_ID = 2;

    private final ItemInventoryDao itemInventoryDao;
    private final ItemInventoryPhotoDao itemInventoryPhotoDao;
    private final ExternalCatalogItemDao externalCatalogItemDao;
    private final ItemInventoryExternalCatalogItemDao itemInventoryExternalCatalogItemDao;
    private final ExternalImageDao externalImageDao;
    private final ExternalImageAlbumDao externalImageAlbumDao;
    private final ExternalImageAlbumImageDao externalImageAlbumImageDao;
    private final ImageHostingSyncProperties properties;
    private final GeneratedDescriptionComposer descriptionComposer;

    @Override
    public ImageHostingDesiredStateSnapshot read(ImageHostingDesiredStateRequest request) {
        if (request == null || request.getItemInventoryId() == null) {
            throw new IllegalArgumentException("itemInventoryId is required");
        }

        ImageHostingSyncProperties.ResolvedProvider provider = properties.resolveProvider(
                request.getProvider(),
                request.getExternalServiceId()
        );

        ItemInventory inventory = itemInventoryDao.findByItemInventoryId(request.getItemInventoryId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No item inventory found for id [%s]".formatted(request.getItemInventoryId())
                ));
        ExternalCatalogItem externalCatalogItem = bricklinkExternalCatalogItem(inventory).orElse(null);
        List<ItemInventoryPhoto> photos = sortedPhotos(itemInventoryPhotoDao.findByItemInventoryId(inventory.getItemInventoryId()));
        DesiredImageHostingAlbum album = desiredAlbum(provider, inventory, externalCatalogItem, photos);
        Set<ExternalImageAlbumImage> albumMemberships = albumMemberships(album);
        Map<Long, ExternalImageAlbumImage> membershipsByExternalImageId = membershipsByExternalImageId(albumMemberships);

        ImageHostingDesiredStateSnapshot.ImageHostingDesiredStateSnapshotBuilder snapshot =
                ImageHostingDesiredStateSnapshot.builder()
                        .provider(provider.provider())
                        .externalServiceId(provider.externalServiceId())
                        .inventory(inventory)
                        .externalCatalogItem(externalCatalogItem)
                        .album(album)
                        .albumMemberships(albumMemberships);

        photos.forEach(photo -> snapshot.photo(desiredPhoto(
                provider.externalServiceId(),
                photo,
                membershipsByExternalImageId
        )));

        return snapshot.build();
    }

    private DesiredImageHostingAlbum desiredAlbum(
            ImageHostingSyncProperties.ResolvedProvider provider,
            ItemInventory inventory,
            ExternalCatalogItem externalCatalogItem,
            List<ItemInventoryPhoto> photos
    ) {
        ExternalImageAlbum externalAlbum =
                externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(
                        provider.externalServiceId(),
                        inventory.getItemInventoryId()
                ).orElse(null);

        return DesiredImageHostingAlbum.builder()
                .desiredTitle(albumTitle(inventory, externalCatalogItem))
                .desiredDescription(ImageHostingAlbumIdentity.withItemReference(
                        descriptionComposer.compose(
                                provider,
                                inventory,
                                externalCatalogItem,
                                externalAlbum,
                                photos
                        ).getDescription(),
                        inventory.getUuid()
                ))
                .externalAlbum(externalAlbum)
                .build();
    }

    private DesiredImageHostingPhoto desiredPhoto(
            Integer externalServiceId,
            ItemInventoryPhoto photo,
            Map<Long, ExternalImageAlbumImage> membershipsByExternalImageId
    ) {
        ExternalImage externalImage =
                externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(
                        externalServiceId,
                        photo.getItemInventoryPhotoId()
                ).orElse(null);

        ExternalImageAlbumImage membership =
                Optional.ofNullable(externalImage)
                        .map(ExternalImage::getExternalImageId)
                        .map(membershipsByExternalImageId::get)
                        .orElse(null);

        return DesiredImageHostingPhoto.builder()
                .inventoryPhoto(photo)
                .externalImage(externalImage)
                .albumMembership(membership)
                .build();
    }

    private Set<ExternalImageAlbumImage> albumMemberships(DesiredImageHostingAlbum album) {
        Long externalImageAlbumId = album.getExternalImageAlbumId();
        if (externalImageAlbumId == null) {
            return Set.of();
        }

        return externalImageAlbumImageDao.findByExternalImageAlbumId(externalImageAlbumId);
    }

    private Map<Long, ExternalImageAlbumImage> membershipsByExternalImageId(Set<ExternalImageAlbumImage> albumMemberships) {
        return albumMemberships.stream()
                .filter(membership -> membership.getExternalImageId() != null)
                .collect(Collectors.toMap(
                        ExternalImageAlbumImage::getExternalImageId,
                        Function.identity(),
                        (first, second) -> first
                ));
    }

    private Optional<ExternalCatalogItem> bricklinkExternalCatalogItem(ItemInventory inventory) {
        return itemInventoryExternalCatalogItemDao.findByItemInventoryId(inventory.getItemInventoryId()).stream()
                .map(ItemInventoryExternalCatalogItem::getExternalCatalogItemId)
                .map(externalCatalogItemDao::findByExternalCatalogItemId)
                .flatMap(Optional::stream)
                .filter(item -> BRICKLINK_SERVICE_ID == item.getExternalServiceId())
                .filter(item -> hasText(item.getExternalItemKey()))
                .filter(item -> hasText(item.getItemName()))
                .findFirst();
    }

    private String albumTitle(ItemInventory inventory, ExternalCatalogItem externalCatalogItem) {
        if (externalCatalogItem != null) {
            return "%s - %s".formatted(externalCatalogItem.getExternalItemKey(), externalCatalogItem.getItemName());
        }

        if (hasText(inventory.getDescription())) {
            return inventory.getDescription();
        }

        return "Inventory %s".formatted(inventory.getUuid());
    }

    private List<ItemInventoryPhoto> sortedPhotos(Set<ItemInventoryPhoto> photos) {
        return photos.stream()
                .sorted(Comparator.comparing(
                        ItemInventoryPhoto::getItemInventoryPhotoId,
                        Comparator.nullsLast(Integer::compareTo)
                ))
                .toList();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
