package io.legohunter.ingress.source.photo.service;

import io.legohunter.egress.imagehosting.removal.HostedContentRemovalService;
import io.legohunter.ingress.common.kafka.event.ObjectDeletedEvent;
import io.legohunter.ingress.config.storage.S3BucketProperties;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.enums.PhotoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoDeletionServiceTest {

    private static final String BUCKET = "lego-photos-sandbox";
    private static final String MD5 = "0123456789abcdef0123456789abcdef";
    private static final String KEY = "3001/4f3fc85a-3c22-4a1a-83f1-528741a5caae/" + MD5 + ".jpg";

    @Mock
    private ItemInventoryPhotoDao itemInventoryPhotoDao;

    @Mock
    private HostedContentRemovalService hostedContentRemovalService;

    private PhotoDeletionService service;

    @BeforeEach
    void setUp() {
        service = new PhotoDeletionService(itemInventoryPhotoDao, s3BucketProperties(BUCKET), hostedContentRemovalService);
    }

    @Test
    void process_physicallyDeletesMatchingPhotoRow() {
        when(itemInventoryPhotoDao.findByMd5(MD5))
                .thenReturn(Optional.of(photo(BUCKET, KEY)));
        when(itemInventoryPhotoDao.deleteByMd5AndStorage(MD5, BUCKET, KEY))
                .thenReturn(1);

        service.process(deleteEvent(BUCKET, KEY));

        verify(itemInventoryPhotoDao)
                .deleteByMd5AndStorage(MD5, BUCKET, KEY);
    }

    @Test
    void process_removesThePublishedCopyBeforeThePhotoRowAndThenReconcilesTheAlbum() {
        ItemInventoryPhoto photo = photo(BUCKET, KEY);
        when(itemInventoryPhotoDao.findByMd5(MD5)).thenReturn(Optional.of(photo));
        when(hostedContentRemovalService.unpublishPhoto(photo)).thenReturn(List.of("flickr-photo-1"));
        when(itemInventoryPhotoDao.deleteByMd5AndStorage(MD5, BUCKET, KEY)).thenReturn(1);

        service.process(deleteEvent(BUCKET, KEY));

        // The link rows reference the photo row, so the published copy goes first.
        InOrder order = inOrder(hostedContentRemovalService, itemInventoryPhotoDao);
        order.verify(hostedContentRemovalService).unpublishPhoto(photo);
        order.verify(itemInventoryPhotoDao).deleteByMd5AndStorage(MD5, BUCKET, KEY);
        order.verify(hostedContentRemovalService).reconcileAlbumAfterRemoval(100);
    }

    @Test
    void process_keepsThePhotoRowWhenTheImageHostRefusesTheDeletion() {
        ItemInventoryPhoto photo = photo(BUCKET, KEY);
        when(itemInventoryPhotoDao.findByMd5(MD5)).thenReturn(Optional.of(photo));
        when(hostedContentRemovalService.unpublishPhoto(photo))
                .thenThrow(new IllegalStateException("Image host refused to delete photo [flickr-photo-1]: unavailable"));

        assertThatThrownBy(() -> service.process(deleteEvent(BUCKET, KEY)))
                .isInstanceOf(IllegalStateException.class);

        verify(itemInventoryPhotoDao, never()).deleteByMd5AndStorage(MD5, BUCKET, KEY);
        verify(hostedContentRemovalService, never()).reconcileAlbumAfterRemoval(any());
    }

    @Test
    void process_isIdempotentWhenPhotoRowDoesNotExist() {
        when(itemInventoryPhotoDao.findByMd5(MD5))
                .thenReturn(Optional.empty());

        service.process(deleteEvent(BUCKET, KEY));

        verify(itemInventoryPhotoDao, never())
                .deleteByMd5AndStorage(MD5, BUCKET, KEY);
        verifyNoInteractions(hostedContentRemovalService);
    }

    @Test
    void process_doesNotDeleteWhenEventStorageDoesNotMatchDatabaseStorage() {
        when(itemInventoryPhotoDao.findByMd5(MD5))
                .thenReturn(Optional.of(photo(BUCKET, "3001/other/" + MD5 + ".jpg")));

        service.process(deleteEvent(BUCKET, KEY));

        verify(itemInventoryPhotoDao, never())
                .deleteByMd5AndStorage(MD5, BUCKET, KEY);
        verifyNoInteractions(hostedContentRemovalService);
    }

    @Test
    void process_rejectsUnsupportedBucketBeforeDatabaseLookup() {
        assertThatThrownBy(() -> service.process(deleteEvent("wrong-bucket", KEY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported photo delete bucket [wrong-bucket]");

        verifyNoInteractions(itemInventoryPhotoDao);
    }

    @Test
    void process_rejectsInvalidFinalPhotoKeyBeforeDatabaseLookup() {
        assertThatThrownBy(() -> service.process(deleteEvent(BUCKET, "photos/" + MD5 + ".jpg")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid final photo key [photos/0123456789abcdef0123456789abcdef.jpg]");

        verifyNoInteractions(itemInventoryPhotoDao);
    }

    @Test
    void extractMd5_returnsLowerCaseMd5FromFinalPhotoKey() {
        String upperCaseKey = "3001/uuid/0123456789ABCDEF0123456789ABCDEF.jpg";

        assertThat(service.extractMd5(upperCaseKey))
                .isEqualTo("0123456789abcdef0123456789abcdef");
    }

    @Test
    void extractMd5_rejectsFilenameThatIsNotMd5() {
        assertThatThrownBy(() -> service.extractMd5("3001/uuid/not-md5.jpg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid MD5 in deleted photo key [3001/uuid/not-md5.jpg]");
    }

    private static ObjectDeletedEvent deleteEvent(String bucket, String key) {
        return ObjectDeletedEvent.builder()
                .bucket(bucket)
                .key(key)
                .eventName("s3:ObjectRemoved:Delete")
                .currentTimeStamp(1L)
                .build();
    }

    private static ItemInventoryPhoto photo(String bucket, String key) {
        return ItemInventoryPhoto.builder()
                .itemInventoryPhotoId(1)
                .itemInventoryId(100)
                .md5(MD5)
                .fileName(MD5 + ".jpg")
                .s3Bucket(bucket)
                .s3Key(key)
                .fileSize(1024L)
                .primary(true)
                .status(PhotoStatus.PROCESSED)
                .build();
    }

    private static S3BucketProperties s3BucketProperties(String finalPhotoBucket) {
        S3BucketProperties properties = new S3BucketProperties();
        S3BucketProperties.Buckets buckets = new S3BucketProperties.Buckets();
        buckets.setFinalPhoto(finalPhotoBucket);
        properties.setBuckets(buckets);
        return properties;
    }
}
