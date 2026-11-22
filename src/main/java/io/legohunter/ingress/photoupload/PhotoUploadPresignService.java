package io.legohunter.ingress.photoupload;

import io.legohunter.ingress.config.storage.S3ClientProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Validates a presign request and signs one PUT URL per file in Silo's uploads bucket (HOMELAB-134). Each refusal is a
 * {@link PhotoUploadRefusedException} with the status it should return. Nothing here logs a URL or a token.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoUploadPresignService {
    private static final String PROD = "prod";
    private static final String CONFIRMATION = "PROD";

    public record FileRequest(String name, long size, String contentType) {
    }

    public record PresignRequest(String environment, List<FileRequest> files, String prodConfirmation) {
    }

    public record SignedUpload(String name, String key, String url) {
    }

    public record PresignResponse(String batchId, List<SignedUpload> uploads) {
    }

    private final PhotoUploadProperties properties;
    private final S3ClientProperties s3Properties;

    public PresignResponse presign(PresignRequest request, String tokenName) {
        String environment = properties.getEnvironment();
        if (request == null || request.environment() == null || !environment.equals(request.environment())) {
            throw new PhotoUploadRefusedException(HttpStatus.FORBIDDEN,
                    "this instance does not upload to the requested environment");
        }
        if (PROD.equals(environment) && !CONFIRMATION.equals(request.prodConfirmation())) {
            throw new PhotoUploadRefusedException(HttpStatus.FORBIDDEN,
                    "a PROD upload needs \"prodConfirmation\": \"PROD\"");
        }
        List<FileRequest> files = request.files();
        if (files == null || files.isEmpty()) {
            throw new PhotoUploadRefusedException(HttpStatus.BAD_REQUEST, "no files were requested");
        }
        if (files.size() > properties.getMaxFiles()) {
            throw new PhotoUploadRefusedException(HttpStatus.BAD_REQUEST,
                    "at most " + properties.getMaxFiles() + " files per request");
        }
        validateFiles(files);

        String batchId = UUID.randomUUID().toString();
        MinioClient silo = siloClient();
        List<SignedUpload> uploads = new ArrayList<>();
        try {
            for (FileRequest file : files) {
                String key = "photos/" + batchId + "/" + file.name();
                String url = silo.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                        .method(Method.PUT)
                        .bucket(properties.uploadsBucket())
                        .object(key)
                        .expiry((int) properties.getPresignExpiry().toSeconds())
                        .build());
                uploads.add(new SignedUpload(file.name(), key, url));
            }
        } catch (Exception e) {
            log.error("photo_upload.presign failed to sign batchId={} reason={}", batchId, e.getClass().getSimpleName());
            throw new PhotoUploadRefusedException(HttpStatus.BAD_GATEWAY, "Silo did not sign the upload URLs");
        }
        log.info("photo_upload.presign tokenName={} environment={} files={} batchId={}",
                tokenName, environment, files.size(), batchId);
        return new PresignResponse(batchId, uploads);
    }

    private void validateFiles(List<FileRequest> files) {
        Set<String> names = new HashSet<>();
        for (FileRequest file : files) {
            String name = file.name();
            if (name == null || name.isEmpty()) {
                throw new PhotoUploadRefusedException(HttpStatus.BAD_REQUEST, "a file has no name");
            }
            if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                throw new PhotoUploadRefusedException(HttpStatus.BAD_REQUEST, "a file name must not contain a folder part");
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
                throw new PhotoUploadRefusedException(HttpStatus.BAD_REQUEST, "only .jpg and .jpeg files are accepted");
            }
            if (file.size() <= 0 || file.size() > properties.getMaxUploadBytes()) {
                throw new PhotoUploadRefusedException(HttpStatus.BAD_REQUEST,
                        "a file must be between 1 and " + properties.getMaxUploadBytes() + " bytes");
            }
            if (file.contentType() != null && !"image/jpeg".equals(file.contentType())) {
                throw new PhotoUploadRefusedException(HttpStatus.BAD_REQUEST, "the content type must be image/jpeg");
            }
            if (!names.add(name)) {
                throw new PhotoUploadRefusedException(HttpStatus.BAD_REQUEST, "a file name is repeated in the request");
            }
        }
    }

    private MinioClient siloClient() {
        if (properties.getSiloUrl() == null || properties.getSiloUrl().isBlank()) {
            throw new PhotoUploadRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "photo upload is not configured");
        }
        return MinioClient.builder()
                .endpoint(properties.getSiloUrl())
                .credentials(s3Properties.getAccessKey(), s3Properties.getSecretKey())
                .build();
    }
}
