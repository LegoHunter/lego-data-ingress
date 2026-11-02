package io.legohunter.ingress.config.environment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every Kubernetes overlay must target its own environment: namespace, instance label, mounted
 * ExternalSecret, hostname and TLS certificate (HOMELAB-26 B3/B4). Since E6 the base names no
 * environment at all, so every overlay (sandbox included) must set each of those values itself;
 * an overlay that forgot one would fail safe (placeholder secret / host) instead of running against,
 * or claiming the routing of, another environment.
 */
class KubernetesOverlayTest {

    private static final String APP = "lego-data-ingress";
    private static final Path KUBERNETES = Path.of("kubernetes");
    private static final Pattern ANY_ENVIRONMENT = Pattern.compile("sandbox|\\bdev\\b|\\bprod\\b");

    private static final String SECRET_NAME = "/spec/template/spec/volumes/0/secret/secretName";
    private static final String HOST_MATCH = "/spec/routes/0/match";
    private static final String TLS_SECRET = "/spec/tls/secretName";

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void overlayTargetsItsOwnNamespaceAndInstanceIncludingThePod(String env) {
        Map<String, Object> kustomization = load("overlays/%s/kustomization.yaml".formatted(env));

        assertThat(kustomization.get("namespace")).isEqualTo("services-" + env);
        assertThat(at(kustomization, "labels", 0, "pairs", "app.kubernetes.io/instance")).isEqualTo(env);
        // The base has no instance label, so the overlay must put it on the pod template too.
        assertThat(at(kustomization, "labels", 0, "includeTemplates")).isEqualTo(true);
    }

    @Test
    void baseNamesNoEnvironment() throws IOException {
        try (Stream<Path> files = Files.list(KUBERNETES.resolve("base"))) {
            assertThat(files.toList()).allSatisfy(file -> assertThat(ANY_ENVIRONMENT.matcher(Files.readString(file)).find())
                    .as("%s mentions an environment", file).isFalse());
        }
        Map<String, Object> deployment = load("base/deployment.yaml");
        Map<String, Object> ingress = load("base/ingress.yaml");

        assertThat(KubernetesOverlayTest.<Map<String, Object>>cast(at(deployment, "spec", "template", "metadata", "labels")))
                .doesNotContainKey("app.kubernetes.io/instance");
        assertThat(at(deployment, "spec", "template", "spec", "containers", 0, "volumeMounts", 0, "name")).isEqualTo("external-secret");
        assertThat(at(deployment, "spec", "template", "spec", "volumes", 0, "name")).isEqualTo("external-secret");
        assertThat(at(deployment, "spec", "template", "spec", "volumes", 0, "secret", "secretName")).isEqualTo("set-by-overlay");
        assertThat(at(ingress, "spec", "routes", 0, "match")).isEqualTo("Host(`%s.set-by-overlay.invalid`)".formatted(APP));
        assertThat(at(ingress, "spec", "tls", "secretName")).isEqualTo("set-by-overlay");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void overlaySetsItsOwnSecret(String env) {
        assertThat(replacements("overlays/%s/deployment-%s-patch.yaml".formatted(env, env)))
                .containsExactlyEntriesOf(Map.of(SECRET_NAME, "services-%s-external-secret".formatted(env)));
        assertThat(patchTargets(env)).containsEntry("deployment-%s-patch.yaml".formatted(env), "Deployment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void overlaySetsItsOwnHostAndTls(String env) {
        assertThat(replacements("overlays/%s/ingress-patch.yaml".formatted(env))).containsExactlyInAnyOrderEntriesOf(Map.of(
                HOST_MATCH, "Host(`%s.%s.internal.legohunter.io`)".formatted(APP, env),
                TLS_SECRET, "%s-internal-legohunter-io-tls".formatted(env)));
        assertThat(patchTargets(env)).containsEntry("ingress-patch.yaml", "IngressRoute");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void overlayCreatesNoNamespaceSharedResources(String env) {
        // The default-headers Middleware is shared by every service in a namespace, so it is created once
        // per namespace from lego-server-configuration, never by a service overlay (HOMELAB-26 E6).
        assertThat(KubernetesOverlayTest.<List<String>>cast(at(load("overlays/%s/kustomization.yaml".formatted(env)), "resources")))
                .containsExactly("../../base");
    }

    /** JSON 6902 patch file as path -> value, asserting every operation is a replace. */
    private Map<String, Object> replacements(String path) {
        List<Map<String, Object>> operations = load(path);
        assertThat(operations).allSatisfy(op -> assertThat(op.get("op")).isEqualTo("replace"));
        return operations.stream().collect(Collectors.toMap(
                op -> (String) op.get("path"), op -> op.get("value"), (a, b) -> b, LinkedHashMap::new));
    }

    /** Kustomization patches that have an explicit target, as patch file -> target kind. */
    @SuppressWarnings("unchecked")
    private Map<String, String> patchTargets(String env) {
        List<Map<String, Object>> patches = (List<Map<String, Object>>) at(load("overlays/%s/kustomization.yaml".formatted(env)), "patches");
        return patches.stream()
                .filter(patch -> patch.get("target") != null)
                .collect(Collectors.toMap(
                        patch -> (String) patch.get("path"),
                        patch -> (String) at(patch, "target", "kind")));
    }

    @SuppressWarnings("unchecked")
    private static Object at(Object node, Object... keys) {
        Object current = node;
        for (Object key : keys) {
            current = key instanceof Integer index ? ((List<Object>) current).get(index) : ((Map<String, Object>) current).get(key);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static <T> T load(String path) {
        try (Reader reader = Files.newBufferedReader(KUBERNETES.resolve(path))) {
            return new Yaml().load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}
