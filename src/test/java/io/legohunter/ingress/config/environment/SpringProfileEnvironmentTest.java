package io.legohunter.ingress.config.environment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each environment's Spring profile must connect to its own database and import the credential
 * files mounted from its own ExternalSecret (HOMELAB-26 B9). A pod runs
 * {@code SPRING_PROFILES_ACTIVE=<env>,kubernetes} (see {@link KubernetesOverlayProfileTest}), so the
 * effective configuration is {@code application.yml}, then {@code application-<env>.yml}, then
 * {@code application-kubernetes.yml}, later files winning. The {@code kubernetes} profile is shared
 * by every environment and must not choose a database.
 *
 * <p>Files are read from {@code src/main/resources} because the test classpath has its own
 * {@code application.yml}.
 */
class SpringProfileEnvironmentTest {

    private static final String RESOURCES = "src/main/resources/";

    private static final List<String> CREDENTIAL_IMPORTS = List.of(
            "file:${import-path}/database-configuration.yml",
            "file:${import-path}/kafka-configuration.yml",
            "file:${import-path}/minio-configuration.yml",
            "file:${import-path}/flickr-configuration.yml",
            "file:${import-path}/bitly-configuration.yml",
            "file:${import-path}/bricklink-client-api-keys.yml",
            "file:${import-path}/shipstation-configuration.yml");

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void podConnectsToItsOwnEnvironmentDatabase(String env) {
        assertThat(podConfiguration(env).getProperty("spring.datasource.database-key-name")).isEqualTo("rasppi5-01-lego-" + env);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void podImportsItsMountedCredentialFiles(String env) {
        Properties effective = podConfiguration(env);

        assertThat(effective.getProperty("import-path")).isEqualTo("/etc/.credentials");
        assertThat(imports(effective)).containsExactlyElementsOf(CREDENTIAL_IMPORTS);
    }

    @Test
    void sharedProfilesDoNotChooseADatabase() {
        for (String shared : new String[]{"application.yml", "application-kubernetes.yml"}) {
            assertThat(load(shared)).as(shared).doesNotContainKey("spring.datasource.database-key-name");
        }
    }

    private static List<String> imports(Properties properties) {
        return IntStream.range(0, CREDENTIAL_IMPORTS.size() + 1)
                .mapToObj(i -> properties.getProperty("spring.config.import[%d]".formatted(i)))
                .takeWhile(value -> value != null)
                .toList();
    }

    private static Properties podConfiguration(String env) {
        Properties effective = new Properties();
        for (String file : new String[]{"application.yml", "application-%s.yml".formatted(env), "application-kubernetes.yml"}) {
            effective.putAll(load(file));
        }
        return effective;
    }

    private static Properties load(String file) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(RESOURCES + file));
        factory.afterPropertiesSet();
        return factory.getObject();
    }
}
