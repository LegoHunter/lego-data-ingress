package io.legohunter.ingress.photoupload;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(PhotoUploadProperties.class)
public class PhotoUploadConfiguration {

    @Bean
    public Clock photoUploadClock() {
        return Clock.systemUTC();
    }
}
