package io.legohunter.ingress.config.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.s3")
public class S3BucketProperties {
    private Buckets buckets = new Buckets();

    public String finalPhotoBucket() {
        if (!StringUtils.hasText(buckets.getFinalPhoto())) {
            throw new IllegalStateException("lego.s3.buckets.final-photo must be configured");
        }
        return buckets.getFinalPhoto().trim();
    }

    @Getter
    @Setter
    public static class Buckets {
        private String finalPhoto;
    }
}
