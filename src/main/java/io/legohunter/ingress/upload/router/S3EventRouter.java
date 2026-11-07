package io.legohunter.ingress.upload.router;

import io.legohunter.ingress.common.kafka.event.ObjectDeletedEvent;
import io.legohunter.ingress.common.kafka.event.ObjectUploadedEvent;
import io.legohunter.ingress.config.storage.S3BucketProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Slf4j
@Component
public class S3EventRouter {

    private static final String PHOTO_UPLOAD_PREFIX = "photos/";
    private static final String REJECTED_PHOTO_PREFIX = "photos/rejected/";
    private static final String DUPLICATE_PHOTO_PREFIX = "photos/duplicate/";
    private static final String JPEG_EXTENSION = ".jpg";
    private static final Pattern MD5_PATTERN = Pattern.compile("^[a-fA-F0-9]{32}$");

    private final KafkaTemplate<String, Object> s3EventRoutingKafkaTemplate;
    private final S3BucketProperties s3BucketProperties;

    public S3EventRouter(
            @Qualifier("s3EventRoutingKafkaTemplate")
            KafkaTemplate<String, Object> s3EventRoutingKafkaTemplate,
            S3BucketProperties s3BucketProperties) {
        this.s3EventRoutingKafkaTemplate = s3EventRoutingKafkaTemplate;
        this.s3BucketProperties = s3BucketProperties;
    }

    @Value("${kafka.topic-configuration.upload-photo.topic}")
    private String photoUploadTopic;

    @Value("${kafka.topic-configuration.delete-photo.topic}")
    private String photoDeleteTopic;

    @Value("${kafka.topic-configuration.upload-bricklink-catalog.topic}")
    private String bricklinkCatalogTopic;

    @Value("${kafka.topic-configuration.upload-bricklink-category.topic}")
    private String bricklinkCategoryTopic;

    @Value("${kafka.topic-configuration.upload-rebrickable-catalog.topic}")
    private String rebrickableCatalogTopic;

    @Value("${kafka.topic-configuration.upload-rebrickable-theme.topic}")
    private String rebrickableThemeTopic;

    public void routeUpload(ObjectUploadedEvent event) {

        String bucket = event.getBucket();
        String key = event.getKey();

        if (isPhotoUploadKey(key)) {
            log.info("Routing upload event {} with key {} to {}", event, key, photoUploadTopic);
            s3EventRoutingKafkaTemplate.send(photoUploadTopic, key, event);
            return;
        }

        if (key.startsWith("bricklink/") || key.startsWith("rebrickable/")) {
            routeDataUpload(event);
            return;
        }

        throw new IllegalArgumentException("Unsupported bucket [%s] and key [%s] for upload routing".formatted(bucket, key));
    }

    public void routeDelete(ObjectDeletedEvent event) {

        String bucket = event.getBucket();
        String key = event.getKey();

        if (s3BucketProperties.finalPhotoBucket().equals(bucket) && isFinalPhotoKey(key)) {
            log.info("Routing delete event {} with key {} to {}", event, key, photoDeleteTopic);
            s3EventRoutingKafkaTemplate.send(photoDeleteTopic, key, event);
            return;
        }

        throw new IllegalArgumentException("Unsupported bucket [%s] and key [%s] for delete routing".formatted(bucket, key));
    }

    private void routeDataUpload(ObjectUploadedEvent event) {

        String bucket = event.getBucket();
        String key = event.getKey();

        if (key.startsWith("bricklink/catalog/")) {
            log.info("Routing upload event {} with key {} to {}", event, key, bricklinkCatalogTopic);
            s3EventRoutingKafkaTemplate.send(bricklinkCatalogTopic, key, event);
            return;
        }

        if (key.startsWith("bricklink/category/")) {
            log.info("Routing upload event {} with key {} to {}", event, key, bricklinkCategoryTopic);
            s3EventRoutingKafkaTemplate.send(bricklinkCategoryTopic, key, event);
            return;
        }

        if (key.startsWith("rebrickable/catalog/")) {
            log.info("Routing upload event {} with key {} to {}", event, key, rebrickableCatalogTopic);
            s3EventRoutingKafkaTemplate.send(rebrickableCatalogTopic, key, event);
            return;
        }

        if (key.startsWith("rebrickable/category/")) {
            log.info("Routing upload event {} with key {} to {}", event, key, rebrickableThemeTopic);
            s3EventRoutingKafkaTemplate.send(rebrickableThemeTopic, key, event);
            return;
        }

        throw new IllegalArgumentException("Unsupported bucket [%s] and key [%s] for upload routing".formatted(bucket, key));
    }

    private boolean isFinalPhotoKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }

        String[] parts = key.split("/");

        if (parts.length != 3
                || parts[0].isBlank()
                || parts[1].isBlank()
                || parts[2].isBlank()
                || !parts[2].toLowerCase().endsWith(JPEG_EXTENSION)) {
            return false;
        }

        String filename = parts[2];
        String md5 = filename.substring(0, filename.length() - JPEG_EXTENSION.length());

        return MD5_PATTERN.matcher(md5).matches();
    }

    private boolean isPhotoUploadKey(String key) {
        return key != null
                && key.startsWith(PHOTO_UPLOAD_PREFIX)
                && !key.startsWith(REJECTED_PHOTO_PREFIX)
                && !key.startsWith(DUPLICATE_PHOTO_PREFIX);
    }
}
