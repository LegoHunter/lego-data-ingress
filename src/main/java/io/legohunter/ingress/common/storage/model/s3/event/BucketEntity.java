package io.legohunter.ingress.common.storage.model.s3.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BucketEntity(
        String name,
        OwnerIdentity ownerIdentity,
        String arn
) {}
