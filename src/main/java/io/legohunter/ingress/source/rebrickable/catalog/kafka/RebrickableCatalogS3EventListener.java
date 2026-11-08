package io.legohunter.ingress.source.rebrickable.catalog.kafka;

import io.legohunter.ingress.common.kafka.event.ObjectUploadedEvent;
import io.legohunter.ingress.source.rebrickable.catalog.model.RebrickableCatalogEntry;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
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
public class RebrickableCatalogS3EventListener {

    private final MinioClient s3Client;
    private final KafkaTemplate<String, RebrickableCatalogEntry> rebrickableCatalogEntryKafkaTemplate;

    @Value("${kafka.topic-configuration.rebrickable-catalog-entry.topic}")
    private String topic;

    @KafkaListener(
            topics = "${kafka.topic-configuration.upload-rebrickable-catalog.topic}",
            groupId = "${kafka.topic-configuration.upload-rebrickable-catalog.consumer.group-id}",
            containerFactory = "uploadRebrickableCatalogContainerFactory")
    public void listen(@Payload ObjectUploadedEvent event) {

        try {
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

                        RebrickableCatalogEntry entry = mapRecord(csvRecord);
                        rebrickableCatalogEntryKafkaTemplate.send(topic, entry);
                        count.getAndIncrement();
                    }

                    log.info("Processed {} Rebrickable catalog entries.", count.get());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        } catch (Exception e) {
            log.error("Failed to deserialize S3 event", e);
        }
    }

    private Integer parseInteger(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Unable to parse integer: {}", value);
            return null;
        }
    }

    private RebrickableCatalogEntry mapRecord(CSVRecord csvRecord) {

        RebrickableCatalogEntry entry = new RebrickableCatalogEntry();

        entry.setSetNum(csvRecord.get("SET_NUM"));
        entry.setName(csvRecord.get("NAME"));
        entry.setYear(parseInteger(csvRecord.get("YEAR")));
        entry.setThemeId(parseInteger(csvRecord.get("THEME_ID")));
        entry.setNumParts(parseInteger(csvRecord.get("NUM_PARTS")));
        entry.setImgUrl(csvRecord.get("IMG_URL"));

        return entry;
    }
}