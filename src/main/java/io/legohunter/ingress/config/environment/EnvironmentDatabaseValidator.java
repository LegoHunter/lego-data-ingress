package io.legohunter.ingress.config.environment;

import io.legohunter.data.environment.EnvironmentDatabasePolicy;
import io.legohunter.ingress.source.bricklink.pricing.BricklinkMarketplaceSyncProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Refuses to start the application when its configured environment does not match the database it
 * is connected to.
 *
 * <p>The production flag switches off every lower-environment guard, so it must agree with the
 * connected schema: PROD only on {@code lego_prod}, SANDBOX only on {@code lego_sandbox}, DEV only on
 * {@code lego_dev}, and local on anything except {@code lego_prod}. The rules are shared with
 * lego-data-service ({@link EnvironmentDatabasePolicy}).
 *
 * <p>The environment code and production flag checked are marketplace sync's;
 * {@code BricklinkMarketplaceSyncEnvironmentValidator} already requires the other copies (pricing
 * apply's environment code, {@code lego.environment.production}) to agree with them.
 *
 * <p>The schema is read from the database at startup, so the application also refuses to start
 * while the database cannot be reached: an unverified environment is treated as unsafe.
 */
@Component
@RequiredArgsConstructor
public class EnvironmentDatabaseValidator implements InitializingBean {

    static final String SETTING_PREFIX = "lego.bricklink.marketplace-sync";

    private final BricklinkMarketplaceSyncProperties marketplaceSyncProperties;
    private final DataSource dataSource;

    @Override
    public void afterPropertiesSet() {
        List<String> violations = EnvironmentDatabasePolicy.violations(
                SETTING_PREFIX,
                marketplaceSyncProperties.effectiveEnvironmentCode(),
                marketplaceSyncProperties.isProduction(),
                connectedSchema(dataSource));
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Configured environment does not match the connected database:\n - "
                    + String.join("\n - ", violations));
        }
    }

    static String connectedSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT DATABASE()")) {
            return resultSet.next() ? resultSet.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot verify the configured environment: reading the connected database schema failed: "
                    + e.getMessage(), e);
        }
    }
}
