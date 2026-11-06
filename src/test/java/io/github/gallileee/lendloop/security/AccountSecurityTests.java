package io.github.gallileee.lendloop.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.gallileee.lendloop.WebTest;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

// Password change, "log out everywhere else", and sessions ending after a password change or
// reset. Each device is a real session from logging in, not a mocked principal.
class AccountSecurityTests extends WebTest {

	private static final String PASSWORD = "kims password 12345";
	private static final String NEW_PASSWORD = "kims new password 678";

	@Autowired
	AppUserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	PasswordResetService passwordResets;

	AppUser kim;

	@BeforeEach
	void createUser() {
		kim = users.save(new AppUser("kim@example.com", "Kim", passwordEncoder.encode(PASSWORD)));
	}

	// Logs in like a browser and returns that browser's session.
	private MockHttpSession logIn(String password) throws Exception {
		return (MockHttpSession) mvc.perform(post("/login").with(csrf())
			.param("email", "kim@example.com")
			.param("password", password))
			.andExpect(authenticated())
			.andReturn()
			.getRequest()
			.getSession(false);
	}

	private ResultActions changePassword(MockHttpSession session, String current, String newPassword,
			String confirm) throws Exception {
		return mvc.perform(post("/settings/password").session(session).with(csrf())
			.param("currentPassword", current)
			.param("newPassword", newPassword)
			.param("confirmPassword", confirm));
	}

	private void stillLoggedIn(MockHttpSession session) throws Exception {
		mvc.perform(get("/items").session(session)).andExpect(status().isOk());
	}

	private void loggedOut(MockHttpSession session) throws Exception {
		mvc.perform(get("/items").session(session)).andExpect(redirectedUrl("/login?ended"));
		// The session itself is gone, so the next request is simply logged out.
		mvc.perform(get("/items").session(session)).andExpect(redirectedUrl("/login"));
	}

	@Test
	void changingThePasswordLogsOutOtherDevicesButNotThisOne() throws Exception {
		MockHttpSession laptop = logIn(PASSWORD);
		MockHttpSession phone = logIn(PASSWORD);
		String laptopIdBefore = laptop.getId();

		changePassword(laptop, PASSWORD, NEW_PASSWORD, NEW_PASSWORD)
			.andExpect(redirectedUrl("/settings"))
			.andExpect(flash().attribute("passwordChanged", true));

		assertThat(laptop.getId()).as("a new session id after the change").isNotEqualTo(laptopIdBefore);
		stillLoggedIn(laptop);
		loggedOut(phone);
		mvc.perform(post("/login").with(csrf()).param("email", "kim@example.com").param("password", PASSWORD))
			.andExpect(unauthenticated());
		logIn(NEW_PASSWORD);
	}

	@Test
	void theLoginPageSaysWhyTheSessionEnded() throws Exception {
		mvc.perform(get("/login").param("ended", ""))
			.andExpect(content().string(containsString("the password was changed or you logged out everywhere")));
	}

	@Test
	void aWrongCurrentPasswordChangesNothing() throws Exception {
		MockHttpSession laptop = logIn(PASSWORD);
		MockHttpSession phone = logIn(PASSWORD);

		changePassword(laptop, "not my password", NEW_PASSWORD, NEW_PASSWORD)
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrorCode("passwordForm", "currentPassword", "wrong"))
			.andExpect(content().string(containsString("That&#39;s not your current password.")))
			// Typed passwords aren't sent back.
			.andExpect(content().string(not(containsString(NEW_PASSWORD))));

		stillLoggedIn(laptop);
		stillLoggedIn(phone);
		logIn(PASSWORD);
	}

	@Test
	void wrongCurrentPasswordsCountLikeFailedLogins() throws Exception {
		MockHttpSession laptop = logIn(PASSWORD);
		for (int i = 0; i < 5; i++) {
			changePassword(laptop, "guess " + i, NEW_PASSWORD, NEW_PASSWORD);
		}

		changePassword(laptop, PASSWORD, NEW_PASSWORD, NEW_PASSWORD)
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrorCode("passwordForm", "currentPassword", "blocked"));
		mvc.perform(post("/login").with(csrf()).param("email", "kim@example.com").param("password", PASSWORD))
			.andExpect(redirectedUrl("/login?blocked"));
	}

	@Test
	void theNewPasswordFollowsTheSignUpRules() throws Exception {
		MockHttpSession laptop = logIn(PASSWORD);

		changePassword(laptop, PASSWORD, "short", "short")
			.andExpect(model().attributeHasFieldErrors("passwordForm", "newPassword"));
		changePassword(laptop, PASSWORD, NEW_PASSWORD, NEW_PASSWORD + "!")
			.andExpect(model().attributeHasFieldErrors("passwordForm", "newPasswordConfirmed"))
			// The rest of the Settings page is still there.
			.andExpect(content().string(containsString("value=\"Kim\"")));
		changePassword(laptop, PASSWORD, "ř".repeat(40), "ř".repeat(40))
			.andExpect(model().attributeHasFieldErrors("passwordForm", "newPasswordShortEnough"));

		logIn(PASSWORD);
	}

	@Test
	void logOutEverywhereElseKeepsOnlyThisBrowser() throws Exception {
		MockHttpSession laptop = logIn(PASSWORD);
		MockHttpSession phone = logIn(PASSWORD);
		MockHttpSession tablet = logIn(PASSWORD);

		mvc.perform(post("/settings/sessions").session(laptop).with(csrf()))
			.andExpect(redirectedUrl("/settings"))
			.andExpect(flash().attribute("sessionsEnded", true));

		stillLoggedIn(laptop);
		loggedOut(phone);
		loggedOut(tablet);
		logIn(PASSWORD);
	}

	@Test
	void aPasswordResetLogsOutEveryDevice() throws Exception {
		MockHttpSession phone = logIn(PASSWORD);
		String token = "t".repeat(43);
		jdbc.update("insert into password_reset_token (user_id, token_hash, expires_at, created_at) values (?, ?, ?, ?)",
				kim.getId(), PasswordResetService.hash(token), Timestamp.from(Instant.now().plusSeconds(600)),
				Timestamp.from(Instant.now()));

		assertThat(passwordResets.resetPassword(token, NEW_PASSWORD)).contains("kim@example.com");

		loggedOut(phone);
	}

	@Test
	void aDeletedAccountsSessionEnds() throws Exception {
		MockHttpSession phone = logIn(PASSWORD);
		users.deleteById(kim.getId());

		loggedOut(phone);
	}

	@Test
	void changingTheNameKeepsTheSession() throws Exception {
		MockHttpSession laptop = logIn(PASSWORD);

		mvc.perform(post("/settings").session(laptop).with(csrf()).param("displayName", "Kimberly"))
			.andExpect(redirectedUrl("/settings"));

		mvc.perform(get("/settings").session(laptop))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Kimberly")));
	}

	@Test
	void bothFormsNeedALoginAndACsrfToken() throws Exception {
		mvc.perform(post("/settings/sessions").with(csrf())).andExpect(redirectedUrl("/login"));
		MockHttpSession laptop = logIn(PASSWORD);
		mvc.perform(post("/settings/sessions").session(laptop)).andExpect(status().isForbidden());
		mvc.perform(post("/settings/password").session(laptop)
			.param("currentPassword", PASSWORD)
			.param("newPassword", NEW_PASSWORD)
			.param("confirmPassword", NEW_PASSWORD))
			.andExpect(status().isForbidden());
		logIn(PASSWORD);
	}
}
