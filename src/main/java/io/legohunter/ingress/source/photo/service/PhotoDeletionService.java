package io.legohunter.ingress.source.photo.service;

import io.legohunter.ingress.common.kafka.event.ObjectDeletedEvent;
import io.legohunter.ingress.config.storage.S3BucketProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ItemInventoryPhoto;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoDeletionService {

    private static final String JPEG_EXTENSION = ".jpg";
    private static final Pattern MD5_PATTERN = Pattern.compile("^[a-fA-F0-9]{32}$");

    private final ItemInventoryPhotoDao itemInventoryPhotoDao;
    private final S3BucketProperties s3BucketProperties;

    public void process(ObjectDeletedEvent event) {

        String bucket = event.getBucket();
        String key = event.getKey();

        validateFinalPhotoDelete(bucket, key);

        String md5 = extractMd5(key);
        MDC.put("md5", md5);

        log.info(
                "photo.delete.process.start bucket={} key={} md5={}",
                bucket,
                key,
                md5
        );

        Optional<ItemInventoryPhoto> existingPhoto =
                itemInventoryPhotoDao.findByMd5(md5);

        if (existingPhoto.isEmpty()) {
            log.info(
                    "photo.delete.db.not_found md5={} bucket={} key={}",
                    md5,
                    bucket,
                    key
            );
            return;
        }

        ItemInventoryPhoto photo = existingPhoto.get();

        if (!bucket.equals(photo.getS3Bucket()) || !key.equals(photo.getS3Key())) {
            log.warn(
                    "photo.delete.db.storage_mismatch md5={} eventBucket={} eventKey={} dbBucket={} dbKey={}",
                    md5,
                    bucket,
                    key,
                    photo.getS3Bucket(),
                    photo.getS3Key()
            );
            return;
        }

        int deleted =
                itemInventoryPhotoDao.deleteByMd5AndStorage(
                        md5,
                        bucket,
                        key
                );

        log.info(
                "photo.delete.db.deleted md5={} bucket={} key={} rows={}",
                md5,
                bucket,
                key,
                deleted
        );
    }

    String extractMd5(String key) {
        validateFinalPhotoDelete(s3BucketProperties.finalPhotoBucket(), key);

        String filename = key.split("/")[2];
        String md5 = filename.substring(0, filename.length() - JPEG_EXTENSION.length());

        if (!MD5_PATTERN.matcher(md5).matches()) {
            throw new IllegalArgumentException("Invalid MD5 in deleted photo key [%s]".formatted(key));
        }

        return md5.toLowerCase();
    }

    private void validateFinalPhotoDelete(String bucket, String key) {
        if (!s3BucketProperties.finalPhotoBucket().equals(bucket)) {
            throw new IllegalArgumentException("Unsupported photo delete bucket [%s]".formatted(bucket));
        }

        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Deleted photo key is missing");
        }

        String[] parts = key.split("/");

        if (parts.length != 3
                || parts[0].isBlank()
                || parts[1].isBlank()
                || parts[2].isBlank()
                || !parts[2].toLowerCase().endsWith(JPEG_EXTENSION)) {
            throw new IllegalArgumentException("Invalid final photo key [%s]".formatted(key));
        }
    }
}
