package io.github.gallileee.lendloop.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.gallileee.lendloop.WebTest;
import io.github.gallileee.lendloop.mail.Mailer;
import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.web.LanguageResolver;
import jakarta.servlet.http.Cookie;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// Each user's emails are written in their own language: the page language at sign-up, then
// whatever they choose in Settings. (The reminder emails are checked in LoanReminderTests.)
class EmailLanguageTests extends WebTest {

	private static final Cookie CZECH = new Cookie(LanguageResolver.COOKIE, "cs");

	@MockitoBean
	Mailer mailer;

	@Autowired
	AppUserRepository users;

	private ResultActions signUp(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.with(csrf())
			.param("email", "kim@example.com")
			.param("displayName", "Kim")
			.param("password", "correct horse battery")
			.param("confirmPassword", "correct horse battery"));
	}

	private AppUser kim() {
		return users.findByEmail("kim@example.com").orElseThrow();
	}

	private String emailText(String subject) {
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mailer).send(eq("kim@example.com"), eq(subject), text.capture(), isNull());
		return text.getValue();
	}

	@Test
	void signingUpOnACzechPageStoresCzechAndSendsACzechConfirmation() throws Exception {
		signUp(post("/register").cookie(CZECH)).andExpect(redirectedUrl("/login"));

		assertThat(kim().getLocale()).isEqualTo(LanguageResolver.CZECH);
		assertThat(emailText("Potvrďte svůj e-mail pro Lendloop"))
			.startsWith("Dobrý den, Kim,")
			.contains("Potvrďte prosím, že je to vaše e-mailová adresa")
			.contains("https://lendloop.test/verify-email?token=")
			.contains("Odkaz funguje 48 hodin.")
			.endsWith("\n\nLendloop\n");
	}

	@Test
	void signingUpOnAnEnglishPageStoresEnglish() throws Exception {
		signUp(post("/register").header("Accept-Language", "en-GB,en;q=0.9"));

		assertThat(kim().getLocale()).isEqualTo(Locale.ENGLISH);
		assertThat(emailText("Confirm your email for Lendloop")).startsWith("Hi Kim,");
	}

	@Test
	void settingsShowAndChangeTheEmailLanguage() throws Exception {
		AppUser alex = users.save(new AppUser("alex@example.com", "Alex", "{noop}x"));

		mvc.perform(get("/settings").with(user(AppUserDetails.from(alex))))
			.andExpect(content().string(containsString("<option value=\"en\" lang=\"en\" selected=\"selected\">")));

		mvc.perform(post("/settings").with(user(AppUserDetails.from(alex))).with(csrf())
				.param("displayName", "Alex")
				.param("emailLanguage", "cs"))
			.andExpect(redirectedUrl("/settings"));
		assertThat(users.findById(alex.getId()).orElseThrow().getLocale()).isEqualTo(LanguageResolver.CZECH);

		// Without the field (an older form, a script), the stored language stays.
		mvc.perform(post("/settings").with(user(AppUserDetails.from(alex))).with(csrf()).param("displayName", "Alex"))
			.andExpect(redirectedUrl("/settings"));
		assertThat(users.findById(alex.getId()).orElseThrow().getLocale()).isEqualTo(LanguageResolver.CZECH);
	}

	@Test
	void anUnsupportedLanguageIsRefused() throws Exception {
		AppUser alex = users.save(new AppUser("alex@example.com", "Alex", "{noop}x"));

		mvc.perform(post("/settings").with(user(AppUserDetails.from(alex))).with(csrf())
				.param("displayName", "Renamed")
				.param("emailLanguage", "de"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Choose English or Czech.")));
		AppUser unchanged = users.findById(alex.getId()).orElseThrow();
		assertThat(unchanged.getLocale()).isEqualTo(Locale.ENGLISH);
		assertThat(unchanged.getDisplayName()).isEqualTo("Alex");
	}

	// The account's language decides, not the page the request came from: whoever asks for the
	// link may not be the account's owner.
	@Test
	void aPasswordResetEmailIsInTheAccountsLanguage() throws Exception {
		AppUser stored = new AppUser("kim@example.com", "Kim", "{noop}x");
		stored.changeLanguage(LanguageResolver.CZECH);
		users.save(stored);

		mvc.perform(post("/forgot-password").with(csrf())
				.header("Accept-Language", "en")
				.param("email", "kim@example.com"))
			.andExpect(redirectedUrl("/forgot-password/sent"));

		assertThat(emailText("Nové heslo pro Lendloop"))
			.startsWith("Dobrý den, Kim,")
			.contains("https://lendloop.test/reset-password?token=")
			.contains("Odkaz funguje jednou, 30 minut.");
		verify(mailer, never()).send(anyString(), eq("Reset your Lendloop password"), anyString(), isNull());
	}
}
