package io.legohunter.ingress.source.lego.woodentoys.capture;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Reads lego.com with plain HTTP requests. A server error (5xx) or a connection failure is tried again after a
 * pause, up to {@code maxAttempts}; any other answer than 200 fails at once.
 */
@Slf4j
public class HttpLegoWoodenToysSource implements LegoWoodenToysSource {
    private final LegoWoodenToysCaptureProperties properties;
    private final HttpClient httpClient;
    private final Pauser pauser;

    public HttpLegoWoodenToysSource(LegoWoodenToysCaptureProperties properties, Pauser pauser) {
        this(properties, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(properties.getConnectTimeout())
                .build(), pauser);
    }

    HttpLegoWoodenToysSource(LegoWoodenToysCaptureProperties properties, HttpClient httpClient, Pauser pauser) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.pauser = pauser;
    }

    @Override
    public String fetchPage(int page) {
        HttpRequest request = request(properties.pageUrl(page), properties.getPageTimeout())
                .header("RSC", "1")
                .build();
        return send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Override
    public void downloadPicture(String url, Path target) {
        if (url == null || !url.startsWith(properties.getPictureUrlPrefix())) {
            throw new SourceException("picture address is outside " + properties.getPictureUrlPrefix() + ": " + url,
                    true, null);
        }
        HttpRequest request;
        try {
            request = request(url, properties.getPictureTimeout()).build();
        } catch (IllegalArgumentException e) {
            throw new SourceException("picture address is not usable: " + url, true, e);
        }
        send(request, HttpResponse.BodyHandlers.ofFile(target, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
    }

    private HttpRequest.Builder request(String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", properties.getUserAgent())
                .GET();
    }

    private <T> T send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        int attempts = Math.max(1, properties.getMaxAttempts());
        for (int attempt = 1; ; attempt++) {
            String failure;
            Throwable cause = null;
            try {
                HttpResponse<T> response = httpClient.send(request, bodyHandler);
                int status = response.statusCode();
                if (status == 200) {
                    return response.body();
                }
                failure = "status " + status;
                if (status < 500) {
                    throw new SourceException(request.uri() + " answered " + failure, true, null);
                }
            } catch (IOException e) {
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                cause = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SourceException(request.uri() + " was interrupted", false, e);
            }
            if (attempt >= attempts) {
                throw new SourceException(request.uri() + " failed after " + attempt + " tries: " + failure, false, cause);
            }
            log.warn("lego_wooden_toys.capture.request_retry uri={} attempt={} failure={}", request.uri(), attempt, failure);
            pauser.pause(properties.getRetryPause());
        }
    }
}
