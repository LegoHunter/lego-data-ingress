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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every Kubernetes overlay must target its own environment: namespace, instance label, mounted
 * ExternalSecret, hostname and TLS certificate (HOMELAB-26 B4). The Spring profile is checked by
 * {@link KubernetesOverlayProfileTest}. The base is
 * written for SANDBOX, so the DEV and PROD overlays must patch each of those values; an overlay
 * that forgets one would run against, or claim the routing of, another environment.
 */
class KubernetesOverlayTest {

    private static final Path KUBERNETES = Path.of("kubernetes");

    private static final String INSTANCE_LABEL = "/spec/template/metadata/labels/app.kubernetes.io~1instance";
    private static final String MOUNT_NAME = "/spec/template/spec/containers/0/volumeMounts/0/name";
    private static final String VOLUME_NAME = "/spec/template/spec/volumes/0/name";
    private static final String SECRET_NAME = "/spec/template/spec/volumes/0/secret/secretName";
    private static final String HOST_MATCH = "/spec/routes/0/match";
    private static final String TLS_SECRET = "/spec/tls/secretName";

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void overlayTargetsItsOwnNamespaceAndInstance(String env) {
        Map<String, Object> kustomization = load("overlays/%s/kustomization.yaml".formatted(env));

        assertThat(kustomization.get("namespace")).isEqualTo("services-" + env);
        assertThat(at(kustomization, "labels", 0, "pairs", "app.kubernetes.io/instance")).isEqualTo(env);
    }

    @Test
    void baseIsWrittenForSandbox() {
        Map<String, Object> deployment = load("base/deployment.yaml");
        Map<String, Object> ingress = load("base/ingress.yaml");

        assertThat(at(deployment, "spec", "template", "metadata", "labels", "app.kubernetes.io/instance"))
                .isEqualTo("sandbox");
        assertThat(at(deployment, "spec", "template", "spec", "containers", 0, "volumeMounts", 0, "name"))
                .isEqualTo("services-sandbox-external-secret");
        assertThat(at(deployment, "spec", "template", "spec", "volumes", 0, "name"))
                .isEqualTo("services-sandbox-external-secret");
        assertThat(at(deployment, "spec", "template", "spec", "volumes", 0, "secret", "secretName"))
                .isEqualTo("services-sandbox-external-secret");
        assertThat(at(ingress, "spec", "routes", 0, "match"))
                .isEqualTo("Host(`lego-data-ingress.sandbox.internal.legohunter.io`)");
        assertThat(at(ingress, "spec", "tls", "secretName")).isEqualTo("sandbox-internal-legohunter-io-tls");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void nonSandboxOverlayRetargetsPodLabelAndSecret(String env) {
        assertThat(replacements("overlays/%s/deployment-%s-patch.yaml".formatted(env, env))).containsExactlyInAnyOrderEntriesOf(Map.of(
                INSTANCE_LABEL, env,
                MOUNT_NAME, "services-%s-external-secret".formatted(env),
                VOLUME_NAME, "services-%s-external-secret".formatted(env),
                SECRET_NAME, "services-%s-external-secret".formatted(env)));
        assertThat(patchTargets(env)).containsEntry("deployment-%s-patch.yaml".formatted(env), "Deployment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void nonSandboxOverlayRetargetsHostAndTls(String env) {
        assertThat(replacements("overlays/%s/ingress-patch.yaml".formatted(env))).containsExactlyInAnyOrderEntriesOf(Map.of(
                HOST_MATCH, "Host(`lego-data-ingress.%s.internal.legohunter.io`)".formatted(env),
                TLS_SECRET, "%s-internal-legohunter-io-tls".formatted(env)));
        assertThat(patchTargets(env)).containsEntry("ingress-patch.yaml", "IngressRoute");
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

    private static <T> T load(String path) {
        try (Reader reader = Files.newBufferedReader(KUBERNETES.resolve(path))) {
            return new Yaml().load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}
