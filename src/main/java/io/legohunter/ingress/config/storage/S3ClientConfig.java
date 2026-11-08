package io.legohunter.ingress.config.storage;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnableConfigurationProperties(S3ClientProperties.class)
@Configuration
@Slf4j
public class S3ClientConfig {

    @Bean
    public MinioClient s3Client(S3ClientProperties s3ClientProperties) {
        log.info("Initializing the S3 client with url {}", s3ClientProperties.getUrl());
        return MinioClient.builder()
                .endpoint(s3ClientProperties.getUrl())
                .credentials(s3ClientProperties.getAccessKey(), s3ClientProperties.getSecretKey())
                .build();
    }
}
