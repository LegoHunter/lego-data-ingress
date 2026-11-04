package io.legohunter.ingress;

import io.legohunter.lego.data.environment.EnvironmentDatabaseVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class LegoDataIngressTests {

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoads() {
	}

	@Test
	void theLegoDataStarterChecksTheEnvironmentAgainstTheDatabase() {
		// The context only starts if the check passed (local on the H2 test database).
		assertThat(context.getBeansOfType(EnvironmentDatabaseVerifier.class)).hasSize(1);
	}

	@Test
	void noDefaultUserIsCreatedSoNoGeneratedPasswordIsLogged(CapturedOutput output) {
		// Spring Security's default in-memory user would log its password in clear text at startup.
		assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
		assertThat(output.getAll()).doesNotContain("generated security password");
	}

}
