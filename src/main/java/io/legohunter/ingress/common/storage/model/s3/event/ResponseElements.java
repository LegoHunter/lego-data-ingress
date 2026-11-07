package io.legohunter.ingress.common.storage.model.s3.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ResponseElements(
        @JsonProperty("x-amz-id-2")
        String xAmzId2,

        @JsonProperty("x-amz-request-id")
        String xAmzRequestId,

        // The S3 server (Silo) still sends these two headers with their x-minio- names; they are
        // part of its compatibility interface.
        @JsonProperty("x-minio-deployment-id")
        String serverDeploymentId,

        @JsonProperty("x-minio-origin-endpoint")
        String serverOriginEndpoint,

        @JsonProperty("content-length")
        long contentLength
) {}
