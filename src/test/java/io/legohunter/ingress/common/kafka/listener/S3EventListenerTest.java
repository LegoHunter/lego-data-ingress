package io.legohunter.ingress.common.kafka.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.ingress.common.kafka.event.ObjectDeletedEvent;
import io.legohunter.ingress.common.kafka.event.ObjectUploadedEvent;
import io.legohunter.ingress.upload.router.S3EventRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class S3EventListenerTest {

    @Mock
    private S3EventRouter s3EventRouter;

    private S3EventListener listener;

    @BeforeEach
    void setUp() {
        listener = new S3EventListener(s3EventRouter, new ObjectMapper());
    }

    @Test
    void handleS3Event_routesObjectCreatedRecordAsUploadEvent() {
        listener.handleS3Event(payload(
                "s3:ObjectCreated:Put",
                "lego-upload-sandbox",
                "photos%2Fitem+one.jpg"
        ));

        ArgumentCaptor<ObjectUploadedEvent> captor = ArgumentCaptor.forClass(ObjectUploadedEvent.class);
        verify(s3EventRouter)
                .routeUpload(captor.capture());

        assertThat(captor.getValue())
                .extracting(
                        ObjectUploadedEvent::getBucket,
                        ObjectUploadedEvent::getKey,
                        ObjectUploadedEvent::getEventName
                )
                .containsExactly(
                        "lego-upload-sandbox",
                        "photos/item one.jpg",
                        "s3:ObjectCreated:Put"
                );

        assertThat(captor.getValue().getCurrentTimeStamp())
                .isPositive();
    }

    @Test
    void handleS3Event_ignoresUnknownServerFields() {
        listener.handleS3Event("""
                {
                  "EventName": "s3:ObjectCreated:Put",
                  "Key": "lego-upload-sandbox/photos/item.jpg",
                  "unexpectedTopLevel": "ignored",
                  "Records": [
                    {
                      "eventName": "s3:ObjectCreated:Put",
                      "unexpectedRecordField": "ignored",
                      "s3": {
                        "unexpectedS3Field": "ignored",
                        "bucket": {
                          "name": "lego-upload-sandbox",
                          "unexpectedBucketField": "ignored"
                        },
                        "object": {
                          "key": "photos%2Fitem.jpg",
                          "size": 1,
                          "eTag": "etag",
                          "contentType": "image/jpeg",
                          "userMetadata": {},
                          "sequencer": "seq",
                          "versionId": "null"
                        }
                      }
                    }
                  ]
                }
                """);

        ArgumentCaptor<ObjectUploadedEvent> captor = ArgumentCaptor.forClass(ObjectUploadedEvent.class);
        verify(s3EventRouter)
                .routeUpload(captor.capture());

        assertThat(captor.getValue())
                .extracting(
                        ObjectUploadedEvent::getBucket,
                        ObjectUploadedEvent::getKey,
                        ObjectUploadedEvent::getEventName
                )
                .containsExactly(
                        "lego-upload-sandbox",
                        "photos/item.jpg",
                        "s3:ObjectCreated:Put"
                );
    }

    @Test
    void handleS3Event_routesObjectRemovedRecordAsDeleteEvent() {
        listener.handleS3Event(payload(
                "s3:ObjectRemoved:Delete",
                "lego-photos-sandbox",
                "3001%2Fuuid%2F0123456789abcdef0123456789abcdef.jpg"
        ));

        ArgumentCaptor<ObjectDeletedEvent> captor = ArgumentCaptor.forClass(ObjectDeletedEvent.class);
        verify(s3EventRouter)
                .routeDelete(captor.capture());

        assertThat(captor.getValue())
                .extracting(
                        ObjectDeletedEvent::getBucket,
                        ObjectDeletedEvent::getKey,
                        ObjectDeletedEvent::getEventName
                )
                .containsExactly(
                        "lego-photos-sandbox",
                        "3001/uuid/0123456789abcdef0123456789abcdef.jpg",
                        "s3:ObjectRemoved:Delete"
                );

        assertThat(captor.getValue().getCurrentTimeStamp())
                .isPositive();
    }

    @Test
    void handleS3Event_ignoresUnhandledEventName() {
        listener.handleS3Event(payload(
                "s3:ObjectAccessed:Get",
                "lego-photos-sandbox",
                "3001%2Fuuid%2F0123456789abcdef0123456789abcdef.jpg"
        ));

        verifyNoInteractions(s3EventRouter);
    }

    @Test
    void handleS3Event_doesNotFailWhenRouterRejectsRecord() {
        doThrow(new IllegalArgumentException("unsupported"))
                .when(s3EventRouter)
                .routeDelete(any(ObjectDeletedEvent.class));

        assertThatCode(() -> listener.handleS3Event(payload(
                "s3:ObjectRemoved:Delete",
                "wrong-bucket",
                "3001%2Fuuid%2F0123456789abcdef0123456789abcdef.jpg"
        )))
                .doesNotThrowAnyException();

        verify(s3EventRouter)
                .routeDelete(any(ObjectDeletedEvent.class));
        verify(s3EventRouter, never())
                .routeUpload(any(ObjectUploadedEvent.class));
    }

    @Test
    void handleS3Event_wrapsInvalidPayload() {
        assertThatThrownBy(() -> listener.handleS3Event("not json"))
                .isInstanceOf(RuntimeException.class);
    }

    private static String payload(String eventName, String bucket, String key) {
        return """
                {
                  "Records": [
                    {
                      "eventName": "%s",
                      "s3": {
                        "bucket": {
                          "name": "%s"
                        },
                        "object": {
                          "key": "%s",
                          "size": 0,
                          "eTag": "etag",
                          "contentType": "image/jpeg",
                          "userMetadata": {},
                          "sequencer": "seq"
                        }
                      }
                    }
                  ]
                }
                """.formatted(eventName, bucket, key);
    }
}
