package io.legohunter.ingress.source.photo.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Limits on a photo taken into the system. A photo is stored exactly as it was uploaded, so its size is set
 * by the export from the photo editor; this limit catches an export made without the agreed size cap before
 * it takes space in the photo store.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "lego.photos")
public class PhotoIngestProperties {
    /** The largest upload that is accepted, in bytes. A larger photo is rejected. Zero or less: no limit. */
    private long maxUploadBytes = 10_000_000L;
}
