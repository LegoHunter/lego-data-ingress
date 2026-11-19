package io.legohunter.ingress.marketplace.bricklink;

import com.sun.net.httpserver.HttpServer;
import io.legohunter.ingress.marketplace.bricklink.BricklinkWebGatewayClient.Answer;
import io.legohunter.ingress.marketplace.bricklink.BricklinkWebGatewayClient.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpBricklinkWebGatewayClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({
            // Cannot work now: nothing wrong with the request, so the caller tries later.
            "503, SESSION_SIGNED_OUT,     CANNOT_WORK",
            "503, SESSION_RECOVERING,     CANNOT_WORK",
            "503, SESSION_UNKNOWN,        CANNOT_WORK",
            "503, LOT_GUARD_UNAVAILABLE,  CANNOT_WORK",
            // Refused for good: a retry cannot help.
            "409, LOT_GUARD_REFUSED,      REFUSED",
            "404, LOT_NOT_FOUND,          REFUSED",
            "400, INVALID_REQUEST,        REFUSED",
            "401, CALLER_TOKEN_REQUIRED,  REFUSED",
            // A retry may fix it.
            "502, UNEXPECTED_PAGE,        FAILED",
            "502, WRITE_NOT_APPLIED,      FAILED",
            "500, INTERNAL,               FAILED"
    })
    void gatewayErrorCodesDecideWhatTheCallerDoes(int status, String code, Kind expected) {
        Answer answer = HttpBricklinkWebGatewayClient.answer(status, "{\"code\":\"" + code + "\",\"message\":\"why\"}");

        assertThat(answer.kind()).isEqualTo(expected);
        assertThat(answer.code()).isEqualTo(code);
        assertThat(answer.message()).isEqualTo("why");
    }

    @Test
    void anAnswerThatIsNotJsonIsJudgedByItsStatusAlone() {
        assertThat(HttpBricklinkWebGatewayClient.answer(200, "{\"lotId\":1,\"customImage\":true}").kind()).isEqualTo(Kind.STORED);
        Answer badGateway = HttpBricklinkWebGatewayClient.answer(502, "<html>Bad Gateway</html>");
        assertThat(badGateway.kind()).isEqualTo(Kind.FAILED);
        assertThat(badGateway.code()).isEqualTo("HTTP_502");
        assertThat(HttpBricklinkWebGatewayClient.answer(503, "").kind()).isEqualTo(Kind.CANNOT_WORK);
    }

    @Test
    void sendsTheImageAsTheMultipartFieldTheGatewayExpectsWithTheCallerToken() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] response = "{\"lotId\":569454190,\"customImage\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        Answer answer = client("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                .putLotImage(569454190L, "lot-569454190.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 'x', 'y'});

        assertThat(answer.kind()).isEqualTo(Kind.STORED);
        assertThat(method.get()).isEqualTo("PUT");
        assertThat(path.get()).isEqualTo("/lots/569454190/image");
        assertThat(authorization.get()).isEqualTo("Bearer test-caller-token");
        assertThat(contentType.get()).startsWith("multipart/form-data");
        assertThat(body.get()).contains("name=\"image\"", "filename=\"lot-569454190.jpg\"", "xy");
    }

    @Test
    void sendsTheExtendedDescriptionAsJsonWithTheCallerToken() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"lotId\":569454190,\"extendedDescription\":\"x\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        Answer answer = client("http://127.0.0.1:" + server.getAddress().getPort())
                .putExtendedDescription(569454190L, "Box has a \"dent\".\nSticker sheet applied.");

        assertThat(answer.kind()).isEqualTo(Kind.STORED);
        assertThat(answer.storedText()).isEqualTo("x");
        assertThat(method.get()).isEqualTo("PUT");
        assertThat(path.get()).isEqualTo("/lots/569454190/extended-description");
        assertThat(authorization.get()).isEqualTo("Bearer test-caller-token");
        assertThat(contentType.get()).startsWith("application/json");
        assertThat(body.get()).isEqualTo("{\"extendedDescription\":\"Box has a \\\"dent\\\".\\nSticker sheet applied.\"}");
    }

    @Test
    void anUnreachableGatewayCannotWorkForTheExtendedDescriptionEither() {
        Answer answer = client("http://127.0.0.1:1").putExtendedDescription(1L, "");

        assertThat(answer.kind()).isEqualTo(Kind.CANNOT_WORK);
        assertThat(answer.code()).isEqualTo(HttpBricklinkWebGatewayClient.UNREACHABLE);
    }

    @Test
    void aGatewayThatCannotBeReachedIsNotARequestFailure() {
        // Nothing listens on port 1.
        Answer answer = client("http://127.0.0.1:1").putLotImage(1L, "lot-1.jpg", new byte[] {1});

        assertThat(answer.kind()).isEqualTo(Kind.CANNOT_WORK);
        assertThat(answer.code()).isEqualTo(HttpBricklinkWebGatewayClient.UNREACHABLE);
        assertThat(answer.message()).doesNotContain("test-caller-token");
    }

    private static HttpBricklinkWebGatewayClient client(String baseUrl) {
        BricklinkWebGatewayProperties properties = new BricklinkWebGatewayProperties();
        properties.setBaseUrl(baseUrl);
        properties.setCallerToken("test-caller-token");
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setRequestTimeout(Duration.ofSeconds(5));
        return new HttpBricklinkWebGatewayClient(properties);
    }
}
