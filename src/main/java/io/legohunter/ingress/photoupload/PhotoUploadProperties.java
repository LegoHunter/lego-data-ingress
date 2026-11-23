package io.legohunter.ingress.photoupload;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for the Lightroom photo upload (HOMELAB-134). Secrets come from the environment's secret store and are
 * never logged. This ingress instance serves one environment only.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "lego.photo-upload")
public class PhotoUploadProperties {
    /** sandbox, dev or prod: the only environment this instance issues tokens for and uploads to. */
    private String environment = "";
    /** HMAC key for stored token hashes. A secret: changing it invalidates every token in this environment. */
    private String tokenSecret = "";
    /**
     * Silo's external S3 endpoint for this environment, as the plugin reaches it. Presigned URLs carry this host.
     * The access key and secret are the existing lego.s3 credentials (the lcms-ingress-<env> user), not copies.
     */
    private String siloUrl = "";
    /** Admin key for /internal/photo-upload/admin. Blank turns the admin path off (it answers 404). A secret. */
    private String adminKey = "";
    /** The uploads bucket is this prefix plus the environment, for example lego-uploads-sandbox. */
    private String uploadsBucketPrefix = "lego-uploads-";
    private int maxFiles = 25;
    private long maxUploadBytes = 10_000_000L;
    private Duration presignExpiry = Duration.ofSeconds(300);
    private Duration accessTokenLifetime = Duration.ofHours(1);
    private Duration refreshTokenLifetime = Duration.ofHours(12);
    /** A batch file still pending this long after its batch was created is reported as failed (upload-design section 13). */
    private Duration statusFailedAfter = Duration.ofMinutes(15);

    public String uploadsBucket() {
        return uploadsBucketPrefix + environment;
    }
}
