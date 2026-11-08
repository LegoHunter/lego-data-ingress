package io.legohunter.ingress.s3.service;

import io.legohunter.ingress.s3.api.S3Service;
import io.legohunter.ingress.s3.exception.S3ObjectNotFoundException;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
@RequiredArgsConstructor
public class S3ServiceImpl implements S3Service {

    private final MinioClient s3Client;

    @Override
    public InputStream getObject(String bucket, String key) {
        try {
            return s3Client.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(key).build()
            );
        } catch (ErrorResponseException e) {
            if (isObjectNotFound(e)) {
                throw new S3ObjectNotFoundException(bucket, key, e);
            }

            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean objectExists(String bucket, String key) {
        if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) {
            return false;
        }

        try {
            s3Client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(key).build()
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void putObject(String bucket, String key, InputStream stream, long size, String contentType) {
        try {
            s3Client.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .stream(stream, size, -1)
                            .contentType(contentType)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void copyObject(String sourceBucket, String sourceKey, String destBucket, String destKey) {
        try {
            s3Client.copyObject(
                    CopyObjectArgs.builder()
                            .source(CopySource.builder().bucket(sourceBucket).object(sourceKey).build())
                            .bucket(destBucket)
                            .object(destKey)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void deleteObject(String bucket, String key) {
        try {
            s3Client.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(key).build()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isObjectNotFound(ErrorResponseException e) {
        return e.errorResponse() != null
                && "NoSuchKey".equals(e.errorResponse().code());
    }
}
