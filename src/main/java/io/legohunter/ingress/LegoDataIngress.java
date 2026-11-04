package io.legohunter.ingress;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * {@code UserDetailsServiceAutoConfiguration} is excluded: Spring Security would otherwise create an
 * in-memory user with a random password and log that password in clear text at every startup. The
 * service never authenticates against it ({@code SecurityConfiguration} disables HTTP Basic and form
 * login), so the user is dead weight and the log line a leaked credential. Excluded here rather than in
 * {@code spring.autoconfigure.exclude}, so a profile that sets that list cannot bring it back.
 */
@SpringBootApplication(
        scanBasePackages = {
                "io.legohunter.ingress",
                "io.legohunter.egress",
                "io.legohunter.imaging.metadata",
                "io.legohunter.imaging.scaling"
        },
        exclude = {
                DataSourceAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
public class LegoDataIngress {

	public static void main(String[] args) {
		SpringApplication.run(LegoDataIngress.class, args);
	}

}
