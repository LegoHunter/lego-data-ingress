package io.legohunter.ingress.common.storage.model.s3.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record S3Event(List<Record> Records) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Record(
            String eventName,
            S3 s3
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record S3(
            Bucket bucket,
            S3Object object
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bucket(
            String name
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record S3Object(
            String key
    ) {
    }
}
