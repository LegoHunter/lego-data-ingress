package io.legohunter.egress.imagehosting.shorturl;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageHostingShortUrlResult {
    private ImageHostingShortUrlStatus status;
    private String albumId;
    private String shortUrl;
    private String message;

    public boolean hasShortUrl() {
        return shortUrl != null && !shortUrl.isBlank();
    }
}
