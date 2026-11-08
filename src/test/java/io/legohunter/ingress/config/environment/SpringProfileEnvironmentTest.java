package io.legohunter.ingress.config.environment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.util.Arrays;
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
            "file:${import-path}/s3-configuration.yml",
            "file:${import-path}/flickr-configuration.yml",
            "file:${import-path}/bitly-configuration.yml",
            "file:${import-path}/bricklink-client-api-keys.yml",
            "file:${import-path}/shipstation-configuration.yml");

    /** Lower environments: the same files, but ShipStation is optional because it is PROD-only (HOMELAB-57/59). */
    private static final List<String> NON_PROD_CREDENTIAL_IMPORTS = List.of(
            "file:${import-path}/database-configuration.yml",
            "file:${import-path}/kafka-configuration.yml",
            "file:${import-path}/s3-configuration.yml",
            "file:${import-path}/flickr-configuration.yml",
            "file:${import-path}/bitly-configuration.yml",
            "file:${import-path}/bricklink-client-api-keys.yml",
            "optional:file:${import-path}/shipstation-configuration.yml");

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void podRunsItsDatabaseSessionsInUtc(String env) {
        // The MySQL server's own zone is Eastern; without this, CURRENT_TIMESTAMP defaults and
        // TIMESTAMP columns disagree with the UTC values the application writes (HOMELAB-36).
        assertThat(podConfiguration(env).getProperty("spring.datasource.hikari.connection-init-sql"))
                .isEqualTo("SET time_zone = '+00:00'");
    }

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
        assertThat(imports(effective)).containsExactlyElementsOf("prod".equals(env) ? CREDENTIAL_IMPORTS : NON_PROD_CREDENTIAL_IMPORTS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev"})
    void lowerEnvironmentsNeverCallShipStation(String env) {
        // ShipStation is PROD-only (HOMELAB-57/59): the fulfillment sync (its only caller) is off, and
        // no ShipStation credentials are configured, so the ShipStation client is never created.
        Properties effective = podConfiguration(env);

        assertThat(String.valueOf(effective.get("lego.fulfillment.sync.scheduled.enabled"))).isEqualTo("false");
        assertThat(effective.stringPropertyNames()).noneMatch(name -> name.startsWith("shipstation.rest.api-"));
    }

    @Test
    void prodKeepsShipStationFulfillment() {
        Properties effective = podConfiguration("prod");

        assertThat(String.valueOf(effective.get("lego.fulfillment.sync.scheduled.enabled"))).isEqualTo("true");
        assertThat(String.valueOf(effective.get("lego.fulfillment.sync.scheduled.apply"))).isEqualTo("true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void podUsesItsOwnEnvironmentS3StoreAndFinalPhotoBucket(String env) {
        Properties effective = podConfiguration(env);

        assertThat(effective.getProperty("lego.s3.url")).isEqualTo(switch (env) {
            // silo-dev serves SANDBOX and DEV (HOMELAB-54).
            case "sandbox", "dev" -> "http://silo-dev.silo-dev.svc.cluster.local:9000";
            case "prod" -> "http://silo-prod.silo-prod.svc.cluster.local:9000";
            default -> throw new IllegalArgumentException(env);
        });
        assertThat(effective.getProperty("lego.s3.buckets.final-photo")).isEqualTo("lego-photos-" + env);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void podConsumesAndRoutesS3EventsOnItsOwnEnvironmentTopics(String env) {
        Properties effective = podConfiguration(env);

        assertThat(effective.getProperty("kafka.topic-configuration.s3-events.topics")).isEqualTo(env + "-s3-events");
        assertThat(effective.getProperty("kafka.topic-configuration.s3-events.consumer.group-id")).isEqualTo(env + "-s3-events-group");
        assertThat(effective.getProperty("kafka.topic-configuration.s3-event-routing.topics").split(",\\s*"))
                .isNotEmpty()
                .allSatisfy(topic -> assertThat(topic).startsWith(env + "-"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void podUsesItsOwnEnvironmentKafkaCluster(String env) {
        // SANDBOX and DEV share redpanda-dev; PROD has its own cluster (HOMELAB-47). Without its own
        // setting, a profile would fall back to application.yml, which points at redpanda-dev.
        String cluster = env.equals("prod") ? "redpanda-prod" : "redpanda-dev";
        assertThat(podConfiguration(env).getProperty("kafka.bootstrap-servers").split(","))
                .containsExactly(IntStream.range(0, 3)
                        .mapToObj(i -> "%s-%d.%s.%s.svc.cluster.local:9092".formatted(cluster, i, cluster, cluster))
                        .toArray(String[]::new));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void everyTopicAndConsumerGroupBelongsToThePodsEnvironment(String env) {
        Properties effective = podConfiguration(env);
        List<String> keys = effective.stringPropertyNames().stream()
                .filter(key -> key.startsWith("kafka.topic-configuration."))
                .toList();
        List<String> topics = keys.stream()
                .filter(key -> key.endsWith(".topic") || key.endsWith(".topics"))
                .flatMap(key -> Arrays.stream(effective.getProperty(key).split(",\\s*")))
                .toList();
        List<String> groups = keys.stream()
                .filter(key -> key.endsWith(".group-id"))
                .map(effective::getProperty)
                .toList();

        assertThat(topics).hasSizeGreaterThan(15).allSatisfy(topic -> assertThat(topic).startsWith(env + "-"));
        assertThat(groups).hasSizeGreaterThan(10).allSatisfy(group -> assertThat(group).startsWith(env + "-").endsWith("-group"));
    }

    @Test
    void prodNeverNamesALowerEnvironmentResource() {
        Properties prod = podConfiguration("prod");
        assertThat(prod.stringPropertyNames().stream()
                .filter(name -> name.startsWith("kafka.") || name.startsWith("lego.s3.")
                        || name.startsWith("spring.datasource.") || name.startsWith("spring.config.import"))
                .map(name -> name + "=" + prod.getProperty(name)))
                .noneMatch(entry -> entry.matches("(?i).*(sandbox|redpanda-dev|minio|-dev\\b|\\bdev-).*"));
    }

    @Test
    void noProfileUsesTheRetiredObjectStoreNames() {
        for (String file : new String[]{"application.yml", "application-sandbox.yml", "application-dev.yml",
                "application-prod.yml", "application-kubernetes.yml", "application-local.yml"}) {
            Properties properties = load(file);
            assertThat(properties.stringPropertyNames())
                    .as(file)
                    .noneMatch(name -> name.startsWith("lego.minio.")
                            || name.startsWith("lego.object-storage.")
                            || name.contains(".minio-s3.")
                            || name.contains(".upload-minio-s3."));
        }
    }

    @Test
    void sandboxRunsTheInventoryReconciliationAndTheLotAuditOnASchedule() {
        Properties sandbox = podConfiguration("sandbox");

        assertThat(String.valueOf(sandbox.get("lego.bricklink.inventory-reconciliation.enabled"))).isEqualTo("true");
        assertThat(String.valueOf(sandbox.get("lego.bricklink.inventory-reconciliation.scheduled.enabled"))).isEqualTo("true");
        assertThat(String.valueOf(sandbox.get("lego.bricklink.lot-audit.enabled"))).isEqualTo("true");
        assertThat(String.valueOf(sandbox.get("lego.bricklink.lot-audit.scheduled.enabled"))).isEqualTo("true");
    }

    @Test
    void theInventoryChecksAreOffUnlessAProfileTurnsThemOn() {
        Properties shared = load("application.yml");

        assertThat(String.valueOf(shared.get("lego.bricklink.inventory-reconciliation.enabled"))).isEqualTo("false");
        assertThat(String.valueOf(shared.get("lego.bricklink.lot-audit.enabled"))).isEqualTo("false");
    }

    @Test
    void sharedProfilesDoNotChooseADatabase() {
        for (String shared : new String[]{"application.yml", "application-kubernetes.yml"}) {
            assertThat(load(shared)).as(shared)
                    .doesNotContainKey("spring.datasource.database-key-name")
                    .doesNotContainKey("lego.s3.url");
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
