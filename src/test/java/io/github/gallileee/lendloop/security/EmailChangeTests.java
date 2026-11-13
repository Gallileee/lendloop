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
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

// "Change email" over HTTP: real login sessions (so ended sessions show), the mailer mocked so
// the emailed link can be read.
class EmailChangeTests extends WebTest {

	private static final String PASSWORD = "kims password 12345";
	private static final String SUBJECT = "Confirm your new email for Lendloop";
	private static final String NOTICE_SUBJECT = "Your Lendloop email address is being changed";
	private static final Pattern LINK = Pattern
		.compile("https://lendloop\\.test/change-email\\?token=([A-Za-z0-9_-]{43})\n");

	@MockitoBean
	Mailer mailer;

	@Autowired
	AppUserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	AppUser kim;

	@BeforeEach
	void createUser() {
		// Not confirmed: changing the address confirms the new one.
		kim = users.save(new AppUser("kim@example.com", "Kim", passwordEncoder.encode(PASSWORD)));
	}

	private MockHttpSession logIn(String email) throws Exception {
		return (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("email", email).param("password", PASSWORD))
			.andExpect(authenticated())
			.andReturn()
			.getRequest()
			.getSession(false);
	}

	private ResultActions requestChange(MockHttpSession session, String newEmail, String password) throws Exception {
		return mvc.perform(post("/settings/email").session(session)
			.with(csrf())
			.param("newEmail", newEmail)
			.param("currentPassword", password));
	}

	// The token from the confirmation email, which must be the only one sent to `to`.
	private String emailedToken(String to) {
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mailer).send(eq(to), eq(SUBJECT), text.capture(), isNull());
		Matcher matcher = LINK.matcher(text.getValue());
		assertThat(matcher.find()).as("link in: %s", text.getValue()).isTrue();
		clearInvocations(mailer);
		return matcher.group(1);
	}

	private String requestedToken(MockHttpSession session, String newEmail) throws Exception {
		requestChange(session, newEmail, PASSWORD).andExpect(redirectedUrl("/settings#change-email"));
		return emailedToken(newEmail);
	}

	private ResultActions confirm(MockHttpSession session, String token) throws Exception {
		var request = post("/change-email").with(csrf()).param("token", token);
		return mvc.perform(session == null ? request : request.session(session));
	}

	private AppUser reload() {
		return users.findById(kim.getId()).orElseThrow();
	}

	private int pendingRows() {
		return jdbc.queryForObject("select count(*) from email_change_token", Integer.class);
	}

	@Test
	void aRequestEmailsALinkToTheNewAddressAndANoticeToTheOldOne() throws Exception {
		MockHttpSession session = logIn("kim@example.com");

		requestChange(session, " Kim.New@Example.com ", PASSWORD).andExpect(redirectedUrl("/settings#change-email"))
			.andExpect(flash().attribute("emailChange", "SENT"));

		ArgumentCaptor<String> notice = ArgumentCaptor.forClass(String.class);
		verify(mailer).send(eq("kim@example.com"), eq(NOTICE_SUBJECT), notice.capture(), isNull());
		assertThat(notice.getValue()).startsWith("Hi Kim,")
			.contains("to kim.new@example.com")
			.contains("cancel the change in Settings")
			.contains("https://lendloop.test/settings")
			.doesNotContain("/change-email");
		String token = emailedToken("kim.new@example.com");
		assertThat(jdbc.queryForObject("select token_hash from email_change_token", String.class))
			.isEqualTo(OneTimeTokens.hash(token));

		// Nothing changes until the link is used.
		assertThat(reload().getEmail()).isEqualTo("kim@example.com");
		mvc.perform(get("/settings").session(session))
			.andExpect(content().string(containsString("Waiting for confirmation: kim.new@example.com")));
	}

	@Test
	void theLinkChangesTheAddressConfirmsItAndEndsOtherSessions() throws Exception {
		MockHttpSession here = logIn("kim@example.com");
		MockHttpSession elsewhere = logIn("kim@example.com");
		String token = requestedToken(here, "kim.new@example.com");

		// Opening the link (as a mail scanner would) only shows the button.
		mvc.perform(get("/change-email").param("token", token))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Use kim.new@example.com as the email address")))
			.andExpect(content().string(containsString("value=\"" + token + "\"")));
		assertThat(reload().getEmail()).isEqualTo("kim@example.com");

		confirm(here, token).andExpect(redirectedUrl("/settings")).andExpect(flash().attribute("emailChanged", true));

		AppUser changed = reload();
		assertThat(changed.getEmail()).isEqualTo("kim.new@example.com");
		assertThat(changed.isEmailVerified()).isTrue();
		assertThat(pendingRows()).isZero();
		mvc.perform(get("/settings").session(here))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("kim.new@example.com")))
			.andExpect(content().string(not(containsString("Waiting for confirmation"))));
		mvc.perform(get("/items").session(elsewhere)).andExpect(redirectedUrl("/login?ended"));

		mvc.perform(post("/login").with(csrf()).param("email", "kim@example.com").param("password", PASSWORD))
			.andExpect(unauthenticated());
		logIn("kim.new@example.com");
	}

	@Test
	void theLinkWorksWithoutALogin() throws Exception {
		String token = requestedToken(logIn("kim@example.com"), "kim.new@example.com");

		confirm(null, token).andExpect(redirectedUrl("/login")).andExpect(flash().attribute("emailChanged", true));
		assertThat(reload().getEmail()).isEqualTo("kim.new@example.com");
	}

	@Test
	void theCurrentPasswordIsNeeded() throws Exception {
		MockHttpSession session = logIn("kim@example.com");

		requestChange(session, "kim.new@example.com", "not my password").andExpect(status().isOk())
			.andExpect(content().string(containsString("That&#39;s not your current password.")))
			.andExpect(content().string(containsString("value=\"kim.new@example.com\"")))
			.andExpect(content().string(not(containsString("not my password"))));
		verify(mailer, never()).send(anyString(), anyString(), anyString(), isNull());
		assertThat(pendingRows()).isZero();
	}

	@Test
	void badNewAddressesAreRefused() throws Exception {
		MockHttpSession session = logIn("kim@example.com");
		users.save(new AppUser("lee@example.com", "Lee", "{noop}irrelevant"));

		requestChange(session, "not an email", PASSWORD)
			.andExpect(content().string(containsString("Enter a valid email address.")));
		requestChange(session, "KIM@example.com", PASSWORD)
			.andExpect(content().string(containsString("That&#39;s already your email address.")));
		requestChange(session, "Lee@Example.com", PASSWORD)
			.andExpect(content().string(containsString("An account with this email already exists.")));

		verify(mailer, never()).send(anyString(), anyString(), anyString(), isNull());
		assertThat(pendingRows()).isZero();
	}

	@Test
	void aNewerRequestReplacesTheOlderOne() throws Exception {
		MockHttpSession session = logIn("kim@example.com");
		String first = requestedToken(session, "kim.first@example.com");
		String second = requestedToken(session, "kim.second@example.com");

		confirm(session, first).andExpect(status().isNotFound());
		confirm(session, second).andExpect(redirectedUrl("/settings"));
		assertThat(reload().getEmail()).isEqualTo("kim.second@example.com");
	}

	@Test
	void cancellingTurnsTheLinkOff() throws Exception {
		MockHttpSession session = logIn("kim@example.com");
		String token = requestedToken(session, "kim.new@example.com");

		mvc.perform(post("/settings/email/cancel").session(session).with(csrf()))
			.andExpect(redirectedUrl("/settings#change-email"))
			.andExpect(flash().attribute("emailChange", "CANCELLED"));

		mvc.perform(get("/change-email").param("token", token)).andExpect(status().isNotFound())
			.andExpect(content().string(containsString("Email change links work once, for 24 hours.")));
		confirm(session, token).andExpect(status().isNotFound());
		assertThat(reload().getEmail()).isEqualTo("kim@example.com");
	}

	@Test
	void expiredUsedAndMadeUpLinksDontWork() throws Exception {
		MockHttpSession session = logIn("kim@example.com");
		String token = requestedToken(session, "kim.new@example.com");
		jdbc.update("update email_change_token set expires_at = ?", Timestamp.from(Instant.now().minusSeconds(1)));

		confirm(session, token).andExpect(status().isNotFound());
		assertThat(reload().getEmail()).isEqualTo("kim@example.com");

		String used = requestedToken(session, "kim.new@example.com");
		confirm(session, used);
		confirm(null, used).andExpect(status().isNotFound());
		mvc.perform(get("/change-email").param("token", "x".repeat(43))).andExpect(status().isNotFound());
		mvc.perform(get("/change-email")).andExpect(status().isNotFound());
	}

	@Test
	void anAddressTakenInTheMeantimeIsNotStolen() throws Exception {
		MockHttpSession session = logIn("kim@example.com");
		String token = requestedToken(session, "kim.new@example.com");
		users.save(new AppUser("kim.new@example.com", "Someone else", "{noop}irrelevant"));

		confirm(session, token).andExpect(status().isConflict())
			.andExpect(content().string(containsString("That address is taken now")));
		assertThat(reload().getEmail()).isEqualTo("kim@example.com");
		assertThat(pendingRows()).isZero();
	}

	@Test
	void linksSentToTheOldAddressStopWorking() throws Exception {
		MockHttpSession session = logIn("kim@example.com");
		mvc.perform(post("/forgot-password").with(csrf()).param("email", "kim@example.com"));
		mvc.perform(post("/settings/email/resend").session(session).with(csrf()));
		assertThat(jdbc.queryForObject("select count(*) from password_reset_token", Integer.class)).isOne();
		assertThat(jdbc.queryForObject("select count(*) from email_verification_token", Integer.class)).isOne();
		clearInvocations(mailer);

		confirm(session, requestedToken(session, "kim.new@example.com"));

		assertThat(jdbc.queryForObject("select count(*) from password_reset_token", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from email_verification_token", Integer.class)).isZero();
	}

	@Test
	void requestsAreLimited() throws Exception {
		MockHttpSession session = logIn("kim@example.com");
		for (int i = 0; i < 3; i++) {
			requestChange(session, "kim" + i + "@example.com", PASSWORD).andExpect(redirectedUrl("/settings#change-email"));
		}
		clearInvocations(mailer);

		requestChange(session, "kim4@example.com", PASSWORD).andExpect(status().isOk())
			.andExpect(content().string(containsString("We&#39;ve sent several links in the last hour.")));
		verify(mailer, never()).send(anyString(), anyString(), anyString(), isNull());
	}

	@Test
	void aFailingMailServerLeavesNothingPending() throws Exception {
		MockHttpSession session = logIn("kim@example.com");
		doThrow(new MailSendException("SMTP server down")).when(mailer)
			.send(anyString(), eq(NOTICE_SUBJECT), anyString(), isNull());

		requestChange(session, "kim.new@example.com", PASSWORD).andExpect(status().isOk())
			.andExpect(content().string(containsString("We couldn&#39;t send the email just now.")));
		assertThat(pendingRows()).isZero();
	}

	@Test
	void requestingNeedsALoginAndACsrfToken() throws Exception {
		mvc.perform(post("/settings/email").with(csrf()).param("newEmail", "x@example.com").param("currentPassword", PASSWORD))
			.andExpect(redirectedUrl("/login"));
		mvc.perform(post("/settings/email").session(logIn("kim@example.com"))
			.param("newEmail", "x@example.com")
			.param("currentPassword", PASSWORD)).andExpect(status().isForbidden());
		mvc.perform(post("/settings/email/cancel").with(csrf())).andExpect(redirectedUrl("/login"));
		verify(mailer, never()).send(anyString(), anyString(), anyString(), isNull());
	}

	@Test
	void theSettingsPageIsTranslated() throws Exception {
		MockHttpSession session = logIn("kim@example.com");
		requestedToken(session, "kim.new@example.com");

		mvc.perform(get("/settings").param("lang", "cs").session(session))
			.andExpect(content().string(containsString("Změnit e-mail")))
			.andExpect(content().string(containsString("Čeká na potvrzení: kim.new@example.com")));
	}
}
