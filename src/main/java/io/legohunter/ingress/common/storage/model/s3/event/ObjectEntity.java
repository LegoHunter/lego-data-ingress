package io.legohunter.ingress.common.storage.model.s3.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ObjectEntity(
        String key,
        long size,
        String eTag,
        String contentType,
        Map<String, String> userMetadata,
        String sequencer
) {}

