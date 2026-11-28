package io.legohunter.ingress.source.lego.woodentoys.capture;

import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dao.LegoWoodenToyCatalogDao;
import io.legohunter.ingress.config.storage.S3BucketProperties;
import io.legohunter.ingress.s3.api.S3Service;
import io.legohunter.ingress.source.capture.CatalogCaptureMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Creates the capture of LEGO's wooden toys catalog when it is enabled in this environment. */
@Configuration
@ConditionalOnProperty(prefix = LegoWoodenToysCaptureProperties.PREFIX, name = "enabled", havingValue = "true")
public class LegoWoodenToysCaptureConfiguration {

    @Bean
    LegoWoodenToysCaptureService legoWoodenToysCaptureService(
            LegoWoodenToysCaptureProperties properties,
            ExternalCatalogItemDao externalCatalogItemDao,
            LegoWoodenToyCatalogDao woodenToyCatalogDao,
            S3Service s3Service,
            S3BucketProperties s3BucketProperties
    ) {
        Pauser pauser = Pauser.sleeping();
        return new LegoWoodenToysCaptureService(
                properties,
                new HttpLegoWoodenToysSource(properties, pauser),
                new LegoWoodenToysPageParser(properties),
                new DaoLegoWoodenToysCatalogState(externalCatalogItemDao, woodenToyCatalogDao),
                s3Service,
                s3BucketProperties.uploadsBucket(),
                pauser,
                Clock.systemUTC()
        );
    }

    @Bean
    LegoWoodenToysCaptureRunner legoWoodenToysCaptureRunner(
            LegoWoodenToysCaptureService captureService,
            CatalogCaptureMetrics metrics
    ) {
        Executor executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lego-wooden-toys-capture");
            thread.setDaemon(true);
            return thread;
        });
        return new LegoWoodenToysCaptureRunner(captureService, metrics, executor);
    }
}
