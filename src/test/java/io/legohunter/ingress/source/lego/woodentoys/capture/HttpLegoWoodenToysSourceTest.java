package io.legohunter.ingress.source.lego.woodentoys.capture;

import com.sun.net.httpserver.HttpServer;
import io.legohunter.ingress.source.lego.woodentoys.capture.LegoWoodenToysSource.SourceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a local HTTP server standing in for lego.com. */
class HttpLegoWoodenToysSourceTest {
    private final LegoWoodenToysCaptureProperties properties = new LegoWoodenToysCaptureProperties();
    private final List<String> requests = new ArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final AtomicInteger pauses = new AtomicInteger();
    private HttpServer server;
    private HttpLegoWoodenToysSource source;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(path + " rsc=" + exchange.getRequestHeaders().getFirst("RSC")
                    + " agent=" + exchange.getRequestHeaders().getFirst("User-Agent"));
            int status = 200;
            if (path.contains("missing")) {
                status = 404;
            } else if (failuresLeft.getAndDecrement() > 0) {
                status = 502;
            }
            byte[] body = ("body of " + path).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.setBaseUrl(base);
        properties.setPictureUrlPrefix(base + "/cdn/");
        source = new HttpLegoWoodenToysSource(properties, duration -> pauses.incrementAndGet());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void theFirstPageHasNoPageNumberAndLaterPagesDo() {
        assertThat(source.fetchPage(1)).isEqualTo("body of /en-us/history/wooden-toys/overview");
        assertThat(source.fetchPage(12)).isEqualTo("body of /en-us/history/wooden-toys/overview/page-12");
        assertThat(requests).allMatch(request -> request.endsWith("rsc=1 agent=lego-data-ingress"));
    }

    @Test
    void aServerErrorIsTriedAgainAfterAPause() {
        failuresLeft.set(2);

        assertThat(source.fetchPage(3)).startsWith("body of");
        assertThat(requests).hasSize(3);
        assertThat(pauses.get()).isEqualTo(2);
    }

    @Test
    void aServerErrorThatOutlastsTheTriesFails() {
        failuresLeft.set(5);

        assertThatThrownBy(() -> source.fetchPage(3))
                .isInstanceOfSatisfying(SourceException.class, e -> assertThat(e.isNotFound()).isFalse())
                .hasMessageContaining("failed after 3 tries: status 502");
        assertThat(requests).hasSize(3);
    }

    @Test
    void notFoundIsNotTriedAgain(@TempDir Path directory) {
        Path target = directory.resolve("picture");

        assertThatThrownBy(() -> source.downloadPicture(properties.getPictureUrlPrefix() + "missing.jpg", target))
                .isInstanceOfSatisfying(SourceException.class, e -> assertThat(e.isNotFound()).isTrue());
        assertThat(requests).hasSize(1);
        assertThat(pauses.get()).isZero();
    }

    @Test
    void aPictureIsDownloadedIntoTheFile(@TempDir Path directory) throws IOException {
        Path target = directory.resolve("picture");
        Files.writeString(target, "left over from an earlier download, and longer than the new content");

        source.downloadPicture(properties.getPictureUrlPrefix() + "assets/bltA/ferry_(2).jpg", target);

        assertThat(Files.readString(target)).isEqualTo("body of /cdn/assets/bltA/ferry_(2).jpg");
        assertThat(requests.get(0)).contains("rsc=null");
    }

    @Test
    void aPictureAddressOutsideThePictureSiteIsRefusedWithoutARequest(@TempDir Path directory) {
        assertThatThrownBy(() -> source.downloadPicture("http://elsewhere.example/picture.jpg", directory.resolve("p")))
                .isInstanceOfSatisfying(SourceException.class, e -> assertThat(e.isNotFound()).isTrue())
                .hasMessageContaining("outside");
        assertThatThrownBy(() -> source.downloadPicture(properties.getPictureUrlPrefix() + "a b.jpg", directory.resolve("p")))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("not usable");
        assertThat(requests).isEmpty();
    }
}
