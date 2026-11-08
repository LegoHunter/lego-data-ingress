package io.legohunter.ingress.common.kafka.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.ingress.common.kafka.event.ObjectDeletedEvent;
import io.legohunter.ingress.common.kafka.event.ObjectUploadedEvent;
import io.legohunter.ingress.common.storage.model.s3.event.Record;
import io.legohunter.ingress.common.storage.model.s3.event.S3Entity;
import io.legohunter.ingress.common.storage.model.s3.event.S3EventNotification;
import io.legohunter.ingress.upload.router.S3EventRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@Slf4j
public class S3EventListener {

    private final S3EventRouter s3EventRouter;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${kafka.topic-configuration.s3-events.topics}",
            groupId = "${kafka.topic-configuration.s3-events.consumer.group-id}",
            containerFactory = "s3EventsContainerFactory"
    )
    public void handleS3Event(@Payload String payload) {
        try {
            S3EventNotification event = objectMapper.readValue(payload, S3EventNotification.class);

            log.info("Received S3Event: {}", event);

            for (Record record : event.Records()) {
                routeRecord(record);
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void routeRecord(Record record) {

        String eventName = record.eventName();
        S3Entity s3Entity = record.s3();
        String bucketName = s3Entity.bucket().name();
        String key = URLDecoder.decode(s3Entity.object().key(), StandardCharsets.UTF_8);

        try {
            if (isObjectCreated(eventName)) {
                s3EventRouter.routeUpload(
                        ObjectUploadedEvent.builder()
                                .bucket(bucketName)
                                .key(key)
                                .eventName(eventName)
                                .currentTimeStamp(System.currentTimeMillis())
                                .build()
                );
                return;
            }

            if (isObjectRemoved(eventName)) {
                s3EventRouter.routeDelete(
                        ObjectDeletedEvent.builder()
                                .bucket(bucketName)
                                .key(key)
                                .eventName(eventName)
                                .currentTimeStamp(System.currentTimeMillis())
                                .build()
                );
                return;
            }

            log.warn("Unhandled S3 eventName={} bucket={} key={}", eventName, bucketName, key);

        } catch (IllegalArgumentException e) {
            log.warn(
                    "S3 event was not routed eventName={} bucket={} key={} reason={}",
                    eventName,
                    bucketName,
                    key,
                    e.getMessage()
            );
        }
    }

    private boolean isObjectCreated(String eventName) {
        return eventName != null && eventName.startsWith("s3:ObjectCreated:");
    }

    private boolean isObjectRemoved(String eventName) {
        return eventName != null && eventName.startsWith("s3:ObjectRemoved:");
    }
}
