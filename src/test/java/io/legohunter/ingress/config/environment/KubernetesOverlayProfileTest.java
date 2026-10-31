package io.legohunter.ingress.config.environment;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each Kubernetes overlay must activate its own Spring profile. The PROD-only order-write gate
 * ({@code lego.environment.production}) is set by {@code application-prod.yml}, so an overlay that
 * activates the wrong profile would silently change which environment may write to real orders.
 */
class KubernetesOverlayProfileTest {

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void overlayActivatesItsOwnProfilePlusKubernetes(String overlay) {
        Properties patch = load("kubernetes/overlays/%s/deployment-env-patch.yaml".formatted(overlay));

        assertThat(patch.getProperty("spec.template.spec.containers[0].env[0].name"))
                .isEqualTo("SPRING_PROFILES_ACTIVE");
        List<String> profiles = Arrays.stream(patch.getProperty("spec.template.spec.containers[0].env[0].value").split(","))
                .map(String::trim)
                .toList();
        assertThat(profiles).containsExactly(overlay, "kubernetes");
    }

    private Properties load(String path) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(path));
        yaml.afterPropertiesSet();
        return yaml.getObject();
    }
}
