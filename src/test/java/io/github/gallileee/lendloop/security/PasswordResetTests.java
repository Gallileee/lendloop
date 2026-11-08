package io.github.gallileee.lendloop.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import io.github.gallileee.lendloop.mail.Mailer;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

// "Forgot my password" over HTTP, with the mailer mocked so the emailed link can be read.
class PasswordResetTests extends WebTest {

	private static final String OLD_PASSWORD = "old password 12345";
	private static final String NEW_PASSWORD = "brand new password 678";
	private static final Pattern LINK = Pattern.compile("https://lendloop\\.test/reset-password\\?token=([A-Za-z0-9_-]{43})\n");

	@MockitoBean
	Mailer mailer;

	@Autowired
	AppUserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@BeforeEach
	void createUser() {
		users.save(new AppUser("kim@example.com", "Kim", passwordEncoder.encode(OLD_PASSWORD)));
	}

	private ResultActions requestLink(String email) throws Exception {
		return mvc.perform(post("/forgot-password").with(csrf()).param("email", email));
	}

	// The token from the only email sent since the last call.
	private String emailedToken() {
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mailer).send(eq("kim@example.com"), eq("Reset your Lendloop password"), text.capture(), isNull());
		clearInvocations(mailer);
		Matcher matcher = LINK.matcher(text.getValue());
		assertThat(matcher.find()).as("link in: %s", text.getValue()).isTrue();
		return matcher.group(1);
	}

	private ResultActions reset(String token, String password, String confirm) throws Exception {
		return mvc.perform(post("/reset-password").with(csrf())
			.param("token", token)
			.param("password", password)
			.param("confirmPassword", confirm));
	}

	private ResultActions login(String password) throws Exception {
		return mvc.perform(post("/login").with(csrf()).param("email", "kim@example.com").param("password", password));
	}

	@Test
	void theEmailedLinkSetsANewPassword() throws Exception {
		mvc.perform(get("/login")).andExpect(content().string(containsString("href=\"/forgot-password\"")));
		requestLink(" Kim@Example.com ").andExpect(redirectedUrl("/forgot-password/sent"));
		String token = emailedToken();

		mvc.perform(get("/reset-password").param("token", token))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Choose a new password")))
			.andExpect(content().string(containsString("value=\"" + token + "\"")));
		reset(token, NEW_PASSWORD, NEW_PASSWORD)
			.andExpect(redirectedUrl("/login"))
			.andExpect(flash().attribute("passwordReset", true));

		login(OLD_PASSWORD).andExpect(unauthenticated());
		login(NEW_PASSWORD).andExpect(authenticated());
	}

	@Test
	void usingAResetLinkAlsoConfirmsTheEmail() throws Exception {
		assertThat(users.findByEmail("kim@example.com").orElseThrow().isEmailVerified()).isFalse();
		requestLink("kim@example.com");

		reset(emailedToken(), NEW_PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/login"));
		assertThat(users.findByEmail("kim@example.com").orElseThrow().isEmailVerified())
			.as("only the owner of the inbox could have used the link").isTrue();
	}

	@Test
	void theEmailExplainsTheLinkAndHasNoUnsubscribeHeader() throws Exception {
		requestLink("kim@example.com");

		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mailer).send(eq("kim@example.com"), anyString(), text.capture(), isNull());
		assertThat(text.getValue())
			.startsWith("Hi Kim,")
			.contains("The link works once, for 30 minutes.")
			.contains("If you didn't ask for this, ignore this email");
	}

	@Test
	void aLinkWorksOnlyOnce() throws Exception {
		requestLink("kim@example.com");
		String token = emailedToken();
		reset(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/login"));

		mvc.perform(get("/reset-password").param("token", token))
			.andExpect(status().isNotFound())
			.andExpect(content().string(containsString("This link doesn&#39;t work any more")));
		reset(token, "a third password 999", "a third password 999").andExpect(status().isNotFound());
		login(NEW_PASSWORD).andExpect(authenticated());
	}

	@Test
	void anUnknownEmailGetsTheSameAnswerButNoEmail() throws Exception {
		requestLink("nobody@example.com")
			.andExpect(redirectedUrl("/forgot-password/sent"))
			.andExpect(flash().attribute("sentTo", "nobody@example.com"));

		verify(mailer, never()).send(anyString(), anyString(), anyString(), any());
		mvc.perform(get("/forgot-password/sent"))
			.andExpect(content().string(containsString("If there&#39;s a Lendloop account for")));
	}

	@Test
	void aNewerLinkReplacesTheOlderOne() throws Exception {
		requestLink("kim@example.com");
		String first = emailedToken();
		requestLink("kim@example.com");
		String second = emailedToken();

		mvc.perform(get("/reset-password").param("token", first)).andExpect(status().isNotFound());
		mvc.perform(get("/reset-password").param("token", second)).andExpect(status().isOk());
	}

	@Test
	void anExpiredLinkIsRefused() throws Exception {
		requestLink("kim@example.com");
		String token = emailedToken();
		jdbc.update("update password_reset_token set expires_at = ?", Timestamp.from(Instant.now().minusSeconds(1)));

		mvc.perform(get("/reset-password").param("token", token)).andExpect(status().isNotFound());
		reset(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isNotFound());
		login(OLD_PASSWORD).andExpect(authenticated());
	}

	@Test
	void onlyAHashOfTheTokenIsStored() throws Exception {
		requestLink("kim@example.com");
		String token = emailedToken();

		String stored = jdbc.queryForObject("select token_hash from password_reset_token", String.class);
		assertThat(stored).isNotEqualTo(token).isEqualTo(OneTimeTokens.hash(token)).hasSize(64);
	}

	@Test
	void theNewPasswordFollowsTheSignUpRulesAndTheLinkStaysUsable() throws Exception {
		requestLink("kim@example.com");
		String token = emailedToken();

		reset(token, "short", "short")
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrors("form", "password"));
		reset(token, NEW_PASSWORD, NEW_PASSWORD + "!")
			.andExpect(model().attributeHasFieldErrors("form", "passwordConfirmed"));
		reset(token, "ř".repeat(40), "ř".repeat(40))
			.andExpect(model().attributeHasFieldErrors("form", "passwordShortEnough"));

		reset(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/login"));
	}

	@Test
	void malformedOrMissingTokensAreRefusedQuietly() throws Exception {
		mvc.perform(get("/reset-password")).andExpect(status().isNotFound());
		mvc.perform(get("/reset-password").param("token", "abc")).andExpect(status().isNotFound());
		mvc.perform(get("/reset-password").param("token", "x".repeat(43))).andExpect(status().isNotFound());
		reset("", NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isNotFound());
	}

	@Test
	void aBlankEmailIsAskedForAgain() throws Exception {
		requestLink("   ")
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Enter the email address you signed up with.")));
		verify(mailer, never()).send(anyString(), anyString(), anyString(), any());
	}

	@Test
	void atMostThreeLinksAnHourAreSentToOneAddress() throws Exception {
		for (int i = 0; i < 4; i++) {
			requestLink("kim@example.com").andExpect(redirectedUrl("/forgot-password/sent"));
		}

		verify(mailer, times(3)).send(eq("kim@example.com"), anyString(), anyString(), isNull());
	}

	@Test
	void aFailedEmailLooksTheSameToTheVisitor() throws Exception {
		doThrow(new MailSendException("SMTP down")).when(mailer).send(anyString(), anyString(), anyString(), any());

		requestLink("kim@example.com").andExpect(redirectedUrl("/forgot-password/sent"));
		assertThat(jdbc.queryForObject("select count(*) from password_reset_token", Integer.class))
			.as("rolled back, since the link never arrived").isZero();
	}

	@Test
	void resettingEndsALoginPause() throws Exception {
		for (int i = 0; i < 5; i++) {
			login("wrong password " + i);
		}
		login(OLD_PASSWORD).andExpect(redirectedUrl("/login?blocked"));

		requestLink("kim@example.com");
		reset(emailedToken(), NEW_PASSWORD, NEW_PASSWORD);

		login(NEW_PASSWORD).andExpect(authenticated());
	}

	@Test
	void askingForALinkNeedsACsrfToken() throws Exception {
		mvc.perform(post("/forgot-password").param("email", "kim@example.com")).andExpect(status().isForbidden());
		verify(mailer, never()).send(anyString(), anyString(), anyString(), any());
	}
}
