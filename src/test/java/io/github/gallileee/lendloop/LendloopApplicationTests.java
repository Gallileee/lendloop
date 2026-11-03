package io.github.gallileee.lendloop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class LendloopApplicationTests {

	// Starts the whole app on H2: Flyway migrates and Hibernate validates the entities against the schema.
	@Test
	void contextLoads() {
	}

}
