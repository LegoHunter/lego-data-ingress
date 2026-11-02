package io.legohunter.ingress.config.environment;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime image and pod are hardened (HOMELAB-26 E8): a JRE-only base image, a numeric non-root
 * user, a read-only root filesystem with /tmp as the only writable path, no privilege escalation and
 * no Linux capabilities. The dev/prod overlays only rename the secret volume (see
 * {@link KubernetesOverlayTest}), so the base Deployment is what every environment runs.
 */
class RuntimeHardeningTest {

    private static final Path KUBERNETES = Path.of("kubernetes");
    private static final long APP_UID = 10001;

    @Test
    void imageIsJreOnlyAndRunsAsNumericNonRootUser() throws IOException {
        List<String> lines = Files.readAllLines(KUBERNETES.resolve("Containerfile")).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();

        assertThat(lines).filteredOn(line -> line.startsWith("FROM "))
                .containsExactly("FROM eclipse-temurin:21-jre-alpine");
        assertThat(lines).filteredOn(line -> line.startsWith("USER "))
                .containsExactly("USER %d:%d".formatted(APP_UID, APP_UID));
        assertThat(lines).contains("ENV HOME=/tmp");
        // No RUN steps: the arm64 image is built on amd64 runners without emulation.
        assertThat(lines).noneMatch(line -> line.startsWith("RUN "));
        assertThat(lines.get(lines.size() - 1)).isEqualTo("ENTRYPOINT [\"java\",\"-jar\",\"/app.jar\"]");
    }

    @Test
    void podNeverRunsAsRoot() {
        Map<String, Object> deployment = load("base/deployment.yaml");

        assertThat(at(deployment, "spec", "template", "spec", "securityContext")).isEqualTo(Map.of(
                "runAsNonRoot", true,
                "runAsUser", (int) APP_UID,
                "runAsGroup", (int) APP_UID,
                "fsGroup", (int) APP_UID,
                "seccompProfile", Map.of("type", "RuntimeDefault")));
    }

    @Test
    void containerHasReadOnlyRootNoEscalationAndNoCapabilities() {
        Map<String, Object> deployment = load("base/deployment.yaml");

        assertThat(at(deployment, "spec", "template", "spec", "containers", 0, "securityContext")).isEqualTo(Map.of(
                "allowPrivilegeEscalation", false,
                "readOnlyRootFilesystem", true,
                "capabilities", Map.of("drop", List.of("ALL"))));
    }

    @Test
    void tmpIsTheOnlyWritableMountAndComesAfterTheSecret() {
        Map<String, Object> deployment = load("base/deployment.yaml");

        // Index 0 stays the credentials secret: the dev/prod JSON patches address volumes/0 and volumeMounts/0.
        assertThat(at(deployment, "spec", "template", "spec", "containers", 0, "volumeMounts", 0, "readOnly")).isEqualTo(true);
        assertThat(at(deployment, "spec", "template", "spec", "containers", 0, "volumeMounts", 1))
                .isEqualTo(Map.of("name", "tmp", "mountPath", "/tmp"));
        assertThat(at(deployment, "spec", "template", "spec", "volumes", 1))
                .isEqualTo(Map.of("name", "tmp", "emptyDir", Map.of("sizeLimit", "512Mi")));
        assertThat((List<?>) at(deployment, "spec", "template", "spec", "volumes")).hasSize(2);
    }

    @SuppressWarnings("unchecked")
    private static Object at(Object node, Object... keys) {
        Object current = node;
        for (Object key : keys) {
            current = key instanceof Integer index ? ((List<Object>) current).get(index) : ((Map<String, Object>) current).get(key);
        }
        return current;
    }

    private static <T> T load(String path) {
        try (Reader reader = Files.newBufferedReader(KUBERNETES.resolve(path))) {
            return new Yaml().load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}
