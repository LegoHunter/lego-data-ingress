package io.legohunter.ingress;

import io.legohunter.lego.data.environment.EnvironmentDatabaseVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
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

}
