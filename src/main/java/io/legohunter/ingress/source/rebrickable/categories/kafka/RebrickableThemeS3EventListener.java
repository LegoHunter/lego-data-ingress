package io.legohunter.ingress.source.rebrickable.categories.kafka;

import io.legohunter.ingress.common.kafka.event.ObjectUploadedEvent;
import io.legohunter.ingress.common.storage.model.s3.event.S3Event;
import io.legohunter.ingress.source.rebrickable.categories.model.RebrickableThemeEntry;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class RebrickableThemeS3EventListener {

    private final MinioClient s3Client;
    private final KafkaTemplate<String, RebrickableThemeEntry> rebrickableThemeEntryKafkaTemplate;

    @Value("${kafka.topic-configuration.rebrickable-theme-entry.topic}")
    private String topic;

    @KafkaListener(
            topics = "${kafka.topic-configuration.upload-rebrickable-theme.topic}",
            groupId = "${kafka.topic-configuration.upload-rebrickable-theme.consumer.group-id}",
            containerFactory = "uploadRebrickableThemeContainerFactory")
    public void listen(@Payload ObjectUploadedEvent event) {
        String bucket = event.getBucket();
        String key = event.getKey();

        log.info("Processing S3 PUT: bucket={}, key={}", bucket, key);

        try (InputStream inputStream = s3Client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {

            CSVParser csvParser = null;
            try {
                log.info("Processing gzip input stream");
                GZIPInputStream gzipStream = new GZIPInputStream(inputStream);
                Reader reader = new InputStreamReader(gzipStream, StandardCharsets.UTF_8);
                log.info("Processing csv file");
                csvParser = new CSVParser(reader,
                        CSVFormat.DEFAULT
                                .withFirstRecordAsHeader()
                                .withIgnoreHeaderCase()
                                .withTrim());

                AtomicInteger count = new AtomicInteger();
                for (CSVRecord csvRecord : csvParser) {

                    RebrickableThemeEntry entry = mapRecord(csvRecord);
                    rebrickableThemeEntryKafkaTemplate.send(topic, entry);
                    count.getAndIncrement();
                }

                log.info("Processed {} Rebrickable category entries.", count.get());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RebrickableThemeEntry mapRecord(CSVRecord csvRecord) {

        RebrickableThemeEntry entry = new RebrickableThemeEntry();

        entry.setId(parseInteger(csvRecord.get("ID")));
        entry.setName(csvRecord.get("NAME"));
        entry.setParentId(parseInteger(csvRecord.get("PARENT_ID")));

        return entry;
    }

    private Integer parseInteger(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Unable to parse integer: {}", value);
            return null;
        }
    }
}
