package io.legohunter.ingress.upload.router;

import io.legohunter.ingress.common.kafka.event.ObjectDeletedEvent;
import io.legohunter.ingress.common.kafka.event.ObjectUploadedEvent;
import io.legohunter.ingress.config.storage.S3BucketProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class S3EventRouterTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private S3EventRouter router;

    @BeforeEach
    void setUp() {
        router = new S3EventRouter(kafkaTemplate, s3BucketProperties("lego-photos-sandbox"));

        ReflectionTestUtils.setField(router, "photoUploadTopic", "sandbox-upload-photo");
        ReflectionTestUtils.setField(router, "photoDeleteTopic", "sandbox-delete-photo");
        ReflectionTestUtils.setField(router, "bricklinkCatalogTopic", "sandbox-upload-bricklink-catalog");
        ReflectionTestUtils.setField(router, "bricklinkCategoryTopic", "sandbox-upload-bricklink-category");
        ReflectionTestUtils.setField(router, "rebrickableCatalogTopic", "sandbox-upload-rebrickable-catalog");
        ReflectionTestUtils.setField(router, "rebrickableThemeTopic", "sandbox-upload-rebrickable-theme");
    }

    @Test
    void routeUpload_routesPhotoUploadToPhotoTopic() {
        ObjectUploadedEvent event = uploadEvent("lego-upload-sandbox", "photos/item.jpg");

        router.routeUpload(event);

        verify(kafkaTemplate)
                .send("sandbox-upload-photo", "photos/item.jpg", event);
    }

    @ParameterizedTest
    @CsvSource({
            "bricklink/catalog/items.xml, sandbox-upload-bricklink-catalog",
            "bricklink/category/categories.xml, sandbox-upload-bricklink-category",
            "rebrickable/catalog/sets.csv, sandbox-upload-rebrickable-catalog",
            "rebrickable/category/themes.csv, sandbox-upload-rebrickable-theme"
    })
    void routeUpload_routesProviderUploadsToProviderTopic(String key, String topic) {
        ObjectUploadedEvent event = uploadEvent("lego-data-sandbox", key);

        router.routeUpload(event);

        verify(kafkaTemplate)
                .send(topic, key, event);
    }

    @Test
    void routeUpload_rejectsUnsupportedUploadKey() {
        ObjectUploadedEvent event = uploadEvent("lego-upload-sandbox", "unsupported/item.jpg");

        assertThatThrownBy(() -> router.routeUpload(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported bucket [lego-upload-sandbox] and key [unsupported/item.jpg]");

        verifyNoInteractions(kafkaTemplate);
    }

    @ParameterizedTest
    @CsvSource({
            "photos/rejected/item.jpg",
            "photos/duplicate/item.jpg"
    })
    void routeUpload_rejectsSpecialPhotoHoldingPrefixes(String key) {
        ObjectUploadedEvent event = uploadEvent("lego-upload-sandbox", key);

        assertThatThrownBy(() -> router.routeUpload(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported bucket [lego-upload-sandbox] and key [%s]".formatted(key));

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void routeDelete_routesFinalPhotoDeleteToDeleteTopic() {
        ObjectDeletedEvent event = deleteEvent(
                "lego-photos-sandbox",
                "3001/4f3fc85a-3c22-4a1a-83f1-528741a5caae/0123456789abcdef0123456789abcdef.jpg"
        );

        router.routeDelete(event);

        verify(kafkaTemplate)
                .send("sandbox-delete-photo", event.getKey(), event);
    }

    @ParameterizedTest
    @CsvSource({
            "wrong-bucket, 3001/uuid/0123456789abcdef0123456789abcdef.jpg",
            "lego-photos-sandbox, photos/0123456789abcdef0123456789abcdef.jpg",
            "lego-photos-sandbox, 3001/uuid/0123456789abcdef0123456789abcdef.png",
            "lego-photos-sandbox, 3001/uuid/not-md5.jpg",
            "lego-photos-sandbox, 3001//0123456789abcdef0123456789abcdef.jpg",
            "lego-photos-sandbox, 3001/uuid/"
    })
    void routeDelete_rejectsNonFinalPhotoDeletes(String bucket, String key) {
        ObjectDeletedEvent event = deleteEvent(bucket, key);

        assertThatThrownBy(() -> router.routeDelete(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported bucket");

        verifyNoInteractions(kafkaTemplate);
    }

    private static ObjectUploadedEvent uploadEvent(String bucket, String key) {
        return ObjectUploadedEvent.builder()
                .bucket(bucket)
                .key(key)
                .eventName("s3:ObjectCreated:Put")
                .currentTimeStamp(1L)
                .build();
    }

    private static ObjectDeletedEvent deleteEvent(String bucket, String key) {
        return ObjectDeletedEvent.builder()
                .bucket(bucket)
                .key(key)
                .eventName("s3:ObjectRemoved:Delete")
                .currentTimeStamp(1L)
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
