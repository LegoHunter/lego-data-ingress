package io.legohunter.ingress.source.bricklink.catalog.kafka;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.legohunter.ingress.common.kafka.event.ObjectUploadedEvent;
import io.legohunter.ingress.source.bricklink.catalog.model.CatalogEntry;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
@Slf4j
public class BricklinkCatalogS3EventListener {

    private final MinioClient s3Client;
    private final XmlMapper xmlMapper;

    private final KafkaTemplate<String, CatalogEntry> bricklinkCatalogEntryKafkaTemplate;

    @Value("${kafka.topic-configuration.bricklink-catalog-entry.topic}")
    private String topic;

    @KafkaListener(
            topics = "${kafka.topic-configuration.upload-bricklink-catalog.topic}",
            groupId = "${kafka.topic-configuration.upload-bricklink-catalog.consumer.group-id}",
            containerFactory = "uploadBricklinkCatalogContainerFactory")
    public void listen(@Payload ObjectUploadedEvent event) {

        try {
            String bucket = event.getBucket();
            String key = event.getKey();

            log.info("Processing S3 PUT: bucket={}, key={}", bucket, key);

            try (InputStream inputStream = s3Client.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(key).build())) {

                try (JsonParser parser = xmlMapper.getFactory().createParser(inputStream)) {
                    log.info("Processing xml input stream");
                    AtomicInteger count = new AtomicInteger();

                    while (parser.nextToken() != null) {
                        if (parser.currentToken() == JsonToken.FIELD_NAME && "ITEM".equals(parser.getCurrentName())) {
                            parser.nextToken();
                            CatalogEntry entry = xmlMapper.readValue(parser, CatalogEntry.class);
                            bricklinkCatalogEntryKafkaTemplate.send(topic, entry);
                            count.getAndIncrement();
                        }
                    }
                    log.info("Processed {} Bricklink catalog entries.", count.get());
                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        } catch (Exception e) {
            log.error("Failed to deserialize S3 event", e);
        }
    }
}
