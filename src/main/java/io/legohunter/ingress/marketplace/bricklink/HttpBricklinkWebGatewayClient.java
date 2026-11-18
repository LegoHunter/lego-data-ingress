package io.legohunter.ingress.marketplace.bricklink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

/** {@link BricklinkWebGatewayClient} over HTTP. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "lego.bricklink.lot-image-sync", name = "enabled", havingValue = "true")
public class HttpBricklinkWebGatewayClient implements BricklinkWebGatewayClient {
    static final String UNREACHABLE = "UNREACHABLE";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BricklinkLotImageSyncProperties properties;
    private final RestClient restClient;

    public HttpBricklinkWebGatewayClient(BricklinkLotImageSyncProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getRequestTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public Answer putLotImage(long lotId, String fileName, byte[] image) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("image", new ByteArrayResource(image) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        try {
            return restClient.put()
                    .uri(baseUrl() + "/lots/{lotId}/image", lotId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getCallerToken())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .exchange((request, response) -> answer(
                            response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            // Connection refused, timeout, no route: the gateway pod is down or restarting.
            log.warn("bricklink.web_gateway.unreachable lotId={} error={}", lotId, e.getClass().getSimpleName());
            return new Answer(Kind.CANNOT_WORK, UNREACHABLE, "The BrickLink web gateway could not be reached: " + e.getClass().getSimpleName());
        }
    }

    /** The gateway answers errors as {"code": "...", "message": "..."}. */
    static Answer answer(int status, String body) {
        if (status == 200) {
            return Answer.stored();
        }
        String code = "HTTP_" + status;
        String message = "The BrickLink web gateway answered HTTP " + status;
        try {
            JsonNode json = MAPPER.readTree(body == null ? "" : body);
            if (json != null && json.hasNonNull("code")) {
                code = json.get("code").asText();
                message = json.path("message").asText(message);
            }
        } catch (Exception ignored) {
            // Not JSON (a proxy error page, for example): the status alone decides.
        }
        return switch (status) {
            // Signed out, recovering, state unknown, or its stockroom lookup is down.
            case 503 -> new Answer(Kind.CANNOT_WORK, code, message);
            // Bad file, bad token, unknown lot, another environment's lot: a retry cannot help.
            case 400, 401, 403, 404, 409, 413, 415 -> new Answer(Kind.REFUSED, code, message);
            default -> new Answer(Kind.FAILED, code, message);
        };
    }

    private String baseUrl() {
        String url = properties.getGatewayBaseUrl().trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
