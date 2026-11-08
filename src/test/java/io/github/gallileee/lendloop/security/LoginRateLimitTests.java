package io.github.gallileee.lendloop.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.gallileee.lendloop.WebTest;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

// Failed-login limits over HTTP, with the default limits (5 per email, 30 per IP, 15 minutes).
class LoginRateLimitTests extends WebTest {

	private static final String PASSWORD = "correct horse battery";

	@Autowired
	AppUserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@BeforeEach
	void createUser() {
		users.save(new AppUser("kim@example.com", "Kim", passwordEncoder.encode(PASSWORD)));
	}

	private ResultActions login(String email, String password, String ip) throws Exception {
		return mvc.perform(post("/login").with(csrf())
			.param("email", email)
			.param("password", password)
			.with(request -> {
				request.setRemoteAddr(ip);
				return request;
			}));
	}

	@Test
	void theFifthWrongPasswordPausesLoginsForThatEmailEvenWithTheRightPassword() throws Exception {
		for (int i = 1; i <= 4; i++) {
			login("kim@example.com", "wrong password " + i, "10.0.0.1").andExpect(redirectedUrl("/login?error"));
		}
		login("kim@example.com", "wrong password 5", "10.0.0.1").andExpect(redirectedUrl("/login?blocked"));

		// The right password, even from another IP: refused without saying whether it was right.
		login("Kim@Example.com", PASSWORD, "10.0.0.2")
			.andExpect(redirectedUrl("/login?blocked"))
			.andExpect(unauthenticated());
	}

	@Test
	void theLoginPageExplainsThePause() throws Exception {
		mvc.perform(get("/login").param("blocked", ""))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Too many failed attempts")))
			.andExpect(content().string(containsString("paused for up to 15 minutes")));
	}

	@Test
	void unknownEmailsAreLimitedTheSameWaySoTheyDontRevealWhichAccountsExist() throws Exception {
		for (int i = 1; i <= 5; i++) {
			login("nobody@example.com", "guess " + i, "10.0.0.1");
		}

		login("nobody@example.com", "guess 6", "10.0.0.3").andExpect(redirectedUrl("/login?blocked"));
	}

	@Test
	void aSuccessfulLoginForgetsEarlierTypos() throws Exception {
		for (int i = 1; i <= 4; i++) {
			login("kim@example.com", "typo " + i, "10.0.0.1");
		}
		login("kim@example.com", PASSWORD, "10.0.0.1").andExpect(authenticated()).andExpect(redirectedUrl("/items"));

		login("kim@example.com", "typo again", "10.0.0.1").andExpect(redirectedUrl("/login?error"));
	}

	@Test
	void oneIpGuessingAtManyAccountsIsPausedForEveryAccount() throws Exception {
		for (int i = 1; i <= 30; i++) {
			login("user" + i + "@example.com", "guess", "10.0.0.9");
		}

		login("kim@example.com", PASSWORD, "10.0.0.9").andExpect(redirectedUrl("/login?blocked"));
		login("kim@example.com", PASSWORD, "10.0.0.10").andExpect(authenticated());
	}

	@Test
	void aLoginWithoutACsrfTokenIsStillRejectedFirstAndNotCounted() throws Exception {
		for (int i = 0; i < 6; i++) {
			mvc.perform(post("/login").param("email", "kim@example.com").param("password", "x"))
				.andExpect(status().isForbidden());
		}

		login("kim@example.com", PASSWORD, "127.0.0.1").andExpect(authenticated());
	}

}
