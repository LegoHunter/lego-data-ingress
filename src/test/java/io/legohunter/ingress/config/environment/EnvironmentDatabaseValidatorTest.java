package io.legohunter.ingress.config.environment;

import io.legohunter.ingress.source.bricklink.pricing.BricklinkMarketplaceSyncProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The startup check reads the schema from a real (H2) connection and refuses to start when the
 * configured environment does not match it. The rule combinations are covered in lego-data's
 * {@code EnvironmentDatabasePolicyTest}; these tests cover the wiring.
 */
class EnvironmentDatabaseValidatorTest {

    @ParameterizedTest(name = "{0} on {2}")
    @CsvSource({
            "sandbox, false, lego_sandbox",
            "dev,     false, lego_dev",
            "prod,    true,  lego_prod",
            "local,   false, lego_sandbox",
            "local,   false, testdb"
    })
    void startsWhenTheEnvironmentMatchesTheConnectedSchema(String code, boolean production, String schema) {
        assertThatCode(() -> validator(code, production, h2(schema)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void refusesALowerEnvironmentConnectedToProd() {
        assertThatThrownBy(() -> validator("sandbox", false, h2("lego_prod")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Configured environment does not match the connected database:\n"
                        + " - lego.bricklink.marketplace-sync.environment-code 'sandbox' must be connected to database schema 'lego_sandbox', not 'LEGO_PROD'");
    }

    @Test
    void refusesLocalConnectedToProd() {
        assertThatThrownBy(() -> validator("local", false, h2("lego_prod")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("environment-code 'local' is not PROD but is connected to PROD's database schema 'LEGO_PROD'");
    }

    @Test
    void refusesProdConnectedToALowerEnvironment() {
        assertThatThrownBy(() -> validator("prod", true, h2("lego_sandbox")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("environment-code 'prod' must be connected to database schema 'lego_prod', not 'LEGO_SANDBOX'");
    }

    @Test
    void refusesTheProductionFlagOnALowerEnvironmentDatabase() {
        assertThatThrownBy(() -> validator("sandbox", true, h2("lego_sandbox")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lego.bricklink.marketplace-sync.production is true but environment-code 'sandbox' is not PROD");
    }

    @Test
    void refusesToStartWhenTheSchemaCannotBeRead() {
        DataSource unreachable = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    throw new SQLException("Communications link failure");
                });

        assertThatThrownBy(() -> validator("sandbox", false, unreachable).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot verify the configured environment: reading the connected database schema failed: Communications link failure");
    }

    private static EnvironmentDatabaseValidator validator(String code, boolean production, DataSource dataSource) {
        BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();
        properties.setEnvironmentCode(code);
        properties.setProduction(production);
        return new EnvironmentDatabaseValidator(properties, dataSource);
    }

    /**
     * An in-memory database named {@code schema}; H2 reports the name upper-cased from
     * {@code SELECT DATABASE()}. It is discarded when the check closes its connection.
     */
    private static DataSource h2(String schema) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + schema);
        dataSource.setUser("sa");
        return dataSource;
    }
}
