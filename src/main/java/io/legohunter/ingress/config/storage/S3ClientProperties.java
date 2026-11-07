package io.legohunter.ingress.config.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lego.s3")
@Getter
@Setter
public class S3ClientProperties {
    private String url;
    private String accessKey;
    private String secretKey;
}
