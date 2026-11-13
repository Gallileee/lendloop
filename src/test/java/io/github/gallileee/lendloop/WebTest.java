package io.github.gallileee.lendloop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gallileee.lendloop.security.EmailChangeService;
import io.github.gallileee.lendloop.security.EmailVerificationService;
import io.github.gallileee.lendloop.security.LoginThrottle;
import io.github.gallileee.lendloop.security.PasswordResetService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// Base class for tests that drive the app over HTTP with MockMvc.
//
// Deliberately NOT @Transactional. A test transaction would wrap every request, keeping the
// Hibernate session open from the controller to the template, so code that reads a lazy
// association after the service's transaction has ended passes in the test and fails in
// production with LazyInitializationException (that happened once; see PROGRESS.md).
// Here each request commits on its own, like it does for real, and the tables are emptied
// after every test instead of rolled back.
@SpringBootTest
@AutoConfigureMockMvc
public abstract class WebTest {

	// Children before parents, so foreign keys never block a delete.
	private static final List<String> TABLES = List.of("loan", "item", "group_member", "app_group",
			"password_reset_token", "email_verification_token", "email_change_token", "app_user");

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	LoginThrottle loginThrottle;

	@Autowired
	PasswordResetService passwordResets;

	@Autowired
	EmailVerificationService emailVerifications;

	@Autowired
	EmailChangeService emailChanges;

	// MockMvc requests all come from one IP; failed logins must not carry over to the next test.
	@AfterEach
	void forgetFailedLogins() {
		loginThrottle.clear();
		passwordResets.clearRequestLimits();
		emailVerifications.clearResendLimits();
		emailChanges.clearRequestLimits();
	}

	@AfterEach
	void emptyTables() {
		for (String table : TABLES) {
			jdbc.update("delete from " + table);
		}
		// A new migration that adds a table must add it above, or its rows would leak into
		// the next test. This fails until it does.
		List<String> appTables = jdbc.queryForList("""
				select table_name from information_schema.tables
				where table_schema = 'public' and table_name <> 'flyway_schema_history'""", String.class);
		assertThat(appTables).as("tables emptied after each WebTest").containsExactlyInAnyOrderElementsOf(TABLES);
	}
}
