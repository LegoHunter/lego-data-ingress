package io.legohunter.ingress.source.bricklink.categories.kafka;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.legohunter.ingress.common.kafka.event.ObjectUploadedEvent;
import io.legohunter.ingress.source.bricklink.categories.model.CategoryEntry;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class BricklinkCategoryS3EventListener {

    private final MinioClient s3Client;
    private final XmlMapper xmlMapper;

    @Qualifier("bricklinkCategoryEntryKafkaTemplate")
    @NonNull
    private final KafkaTemplate<String, CategoryEntry> bricklinkCategoryEntryKafkaTemplate;

    @Value("${kafka.topic-configuration.bricklink-category-entry.topic}")
    private String topic;

    @KafkaListener(
            topics = "${kafka.topic-configuration.upload-bricklink-category.topic}",
            groupId = "${kafka.topic-configuration.upload-bricklink-category.consumer.group-id}",
            containerFactory = "uploadBricklinkCategoryContainerFactory")
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
                            CategoryEntry entry = xmlMapper.readValue(parser, CategoryEntry.class);
                            bricklinkCategoryEntryKafkaTemplate.send(topic, entry);
                            count.getAndIncrement();
                        }
                    }
                    log.info("Processed {} Bricklink category entries.", count.get());
                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        } catch (Exception e) {
            log.error("Failed to deserialize S3 event", e);
        }
    }
}
