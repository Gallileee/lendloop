package io.github.gallileee.lendloop.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// "Confirm your email" over HTTP, with the mailer mocked so the emailed link can be read.
class EmailVerificationTests extends WebTest {

	private static final String SUBJECT = "Confirm your email for Lendloop";
	private static final Pattern LINK = Pattern.compile("https://lendloop\\.test/verify-email\\?token=([A-Za-z0-9_-]{43})\n");

	@MockitoBean
	Mailer mailer;

	@Autowired
	AppUserRepository users;

	private ResultActions signUp(String email) throws Exception {
		return mvc.perform(post("/register").with(csrf())
			.param("email", email)
			.param("displayName", "Kim")
			.param("password", "correct horse battery")
			.param("confirmPassword", "correct horse battery"));
	}

	private AppUser kim() {
		return users.findByEmail("kim@example.com").orElseThrow();
	}

	private static RequestPostProcessor as(AppUser user) {
		return user(AppUserDetails.from(user));
	}

	// The token from the only email sent since the last call.
	private String emailedToken() {
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mailer).send(eq("kim@example.com"), eq(SUBJECT), text.capture(), isNull());
		clearInvocations(mailer);
		Matcher matcher = LINK.matcher(text.getValue());
		assertThat(matcher.find()).as("link in: %s", text.getValue()).isTrue();
		return matcher.group(1);
	}

	private ResultActions confirm(String token) throws Exception {
		return mvc.perform(post("/verify-email").with(csrf()).param("token", token));
	}

	private ResultActions resend(AppUser user) throws Exception {
		return mvc.perform(post("/settings/email/resend").with(as(user)).with(csrf()));
	}

	@Test
	void signingUpEmailsALinkThatConfirmsTheAddress() throws Exception {
		signUp("Kim@Example.com").andExpect(redirectedUrl("/login"));
		assertThat(kim().isEmailVerified()).isFalse();
		String token = emailedToken();

		// Opening the link (as a mail scanner would) only shows the button.
		mvc.perform(get("/verify-email").param("token", token))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Confirm my email")))
			.andExpect(content().string(containsString("value=\"" + token + "\"")));
		assertThat(kim().isEmailVerified()).isFalse();

		confirm(token).andExpect(redirectedUrl("/login")).andExpect(flash().attribute("emailVerified", true));
		assertThat(kim().isEmailVerified()).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from email_verification_token", Integer.class)).isZero();
	}

	@Test
	void theEmailExplainsWhatTheLinkIsFor() throws Exception {
		signUp("kim@example.com");

		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mailer).send(eq("kim@example.com"), eq(SUBJECT), text.capture(), isNull());
		assertThat(text.getValue())
			.startsWith("Hi Kim,")
			.contains("won't send you reminders")
			.contains("works for 48 hours")
			.contains("If you didn't sign up, ignore this email");
	}

	@Test
	void aLoggedInUserGoesBackToSettings() throws Exception {
		signUp("kim@example.com");
		String token = emailedToken();

		mvc.perform(post("/verify-email").with(as(kim())).with(csrf()).param("token", token))
			.andExpect(redirectedUrl("/settings"));
		mvc.perform(get("/settings").with(as(kim())))
			.andExpect(content().string(containsString("Confirmed")))
			.andExpect(content().string(not(containsString("Send the link again"))));
	}

	@Test
	void aLinkWorksOnceAndBrokenLinksSaySo() throws Exception {
		signUp("kim@example.com");
		String token = emailedToken();
		confirm(token);

		confirm(token).andExpect(status().isNotFound())
			.andExpect(content().string(containsString("This link doesn't work any more")));
		mvc.perform(get("/verify-email").param("token", token)).andExpect(status().isNotFound());
		mvc.perform(get("/verify-email").param("token", "not a token")).andExpect(status().isNotFound());
		mvc.perform(get("/verify-email")).andExpect(status().isNotFound());
	}

	@Test
	void anExpiredLinkIsRefused() throws Exception {
		signUp("kim@example.com");
		String token = emailedToken();
		jdbc.update("update email_verification_token set expires_at = ?", Timestamp.from(Instant.now().minusSeconds(1)));

		confirm(token).andExpect(status().isNotFound());
		assertThat(kim().isEmailVerified()).isFalse();
	}

	@Test
	void theStoredTokenIsAHashNotTheLink() throws Exception {
		signUp("kim@example.com");
		String token = emailedToken();

		String stored = jdbc.queryForObject("select token_hash from email_verification_token", String.class);
		assertThat(stored).isNotEqualTo(token).isEqualTo(OneTimeTokens.hash(token));
	}

	@Test
	void everyPageAsksToConfirmUntilItIsDone() throws Exception {
		signUp("kim@example.com");
		String token = emailedToken();

		mvc.perform(get("/items").with(as(kim())))
			.andExpect(content().string(containsString("Confirm your email address to get loan reminders")));
		mvc.perform(get("/settings").with(as(kim())))
			.andExpect(content().string(containsString("Not confirmed")))
			.andExpect(content().string(containsString("Send the link again")));

		confirm(token);
		mvc.perform(get("/items").with(as(kim())))
			.andExpect(content().string(not(containsString("Confirm your email address"))));
	}

	@Test
	void aNewLinkReplacesTheOldOne() throws Exception {
		signUp("kim@example.com");
		String first = emailedToken();

		resend(kim()).andExpect(redirectedUrl("/settings#email")).andExpect(flash().attribute("verification", "SENT"));
		String second = emailedToken();

		confirm(first).andExpect(status().isNotFound());
		confirm(second).andExpect(redirectedUrl("/login"));
		assertThat(kim().isEmailVerified()).isTrue();
	}

	@Test
	void askingAgainIsLimited() throws Exception {
		signUp("kim@example.com");
		clearInvocations(mailer);

		for (int i = 0; i < 3; i++) {
			resend(kim()).andExpect(flash().attribute("verification", "SENT"));
		}
		resend(kim()).andExpect(flash().attribute("verification", "TOO_MANY"));
		verify(mailer, times(3)).send(anyString(), eq(SUBJECT), anyString(), isNull());
	}

	@Test
	void aConfirmedAddressGetsNoMoreLinks() throws Exception {
		signUp("kim@example.com");
		confirm(emailedToken());

		resend(kim()).andExpect(flash().attribute("verification", "ALREADY_VERIFIED"));
		verify(mailer, never()).send(anyString(), anyString(), anyString(), isNull());
	}

	@Test
	void aFailingMailServerStillCreatesTheAccount() throws Exception {
		doThrow(new MailSendException("SMTP server down")).when(mailer)
			.send(anyString(), eq(SUBJECT), anyString(), isNull());

		signUp("kim@example.com").andExpect(redirectedUrl("/login"));
		assertThat(kim().isEmailVerified()).isFalse();
		// The token of the email that failed is rolled back with it.
		assertThat(jdbc.queryForObject("select count(*) from email_verification_token", Integer.class)).isZero();

		resend(kim()).andExpect(flash().attribute("verification", "MAIL_FAILED"));
	}

	@Test
	void resendingNeedsALoginAndACsrfToken() throws Exception {
		signUp("kim@example.com");
		clearInvocations(mailer);

		mvc.perform(post("/settings/email/resend").with(csrf())).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/settings/email/resend").with(as(kim()))).andExpect(status().isForbidden());
		verify(mailer, never()).send(anyString(), anyString(), anyString(), isNull());
	}
}
