package io.legohunter.ingress.common.storage.model.s3.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Record(
        String eventVersion,
        String eventSource,
        String awsRegion,
        String eventTime,
        String eventName,
        UserIdentity userIdentity,
        RequestParameters requestParameters,
        ResponseElements responseElements,
        S3Entity s3,
        Source source
) {}

