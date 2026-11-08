package io.github.gallileee.lendloop.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.gallileee.lendloop.WebTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

class LanguageTests extends WebTest {

	private static final Cookie CZECH = new Cookie(LanguageResolver.COOKIE, "cs");

	@Test
	void withoutAnyPreferenceThePagesAreEnglish() throws Exception {
		mvc.perform(get("/login"))
			.andExpect(status().isOk())
			.andExpect(content().string(allOf(containsString("<html lang=\"en\""),
					containsString("Forgot your password?"), containsString("Čeština"))));
	}

	@Test
	void aCzechBrowserGetsCzechPages() throws Exception {
		mvc.perform(get("/login").header("Accept-Language", "cs-CZ,cs;q=0.9,en;q=0.8"))
			.andExpect(content().string(allOf(containsString("<html lang=\"cs\""),
					containsString("Zapomněli jste heslo?"), not(containsString("Forgot your password?")))));
	}

	@Test
	void anUnsupportedBrowserLanguageFallsBackToTheBestSupportedOneOrEnglish() throws Exception {
		mvc.perform(get("/login").header("Accept-Language", "de-DE,de;q=0.9,cs;q=0.5"))
			.andExpect(content().string(containsString("Zapomněli jste heslo?")));
		mvc.perform(get("/login").header("Accept-Language", "de-DE,fr;q=0.5"))
			.andExpect(content().string(containsString("<html lang=\"en\"")));
	}

	@Test
	void theSwitcherSetsACookieThatWinsOverTheBrowserLanguage() throws Exception {
		mvc.perform(get("/login").param("lang", "cs").header("Accept-Language", "en"))
			.andExpect(content().string(containsString("Přihlásit se")))
			.andExpect(cookie().value(LanguageResolver.COOKIE, "cs"))
			.andExpect(cookie().httpOnly(LanguageResolver.COOKIE, true))
			.andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")));

		mvc.perform(get("/register").cookie(CZECH).header("Accept-Language", "en"))
			.andExpect(content().string(containsString("Založení účtu")));

		mvc.perform(get("/register").param("lang", "en").cookie(CZECH))
			.andExpect(content().string(containsString("Create an account")))
			.andExpect(cookie().value(LanguageResolver.COOKIE, "en"));
	}

	@Test
	void unknownLanguagesAreIgnored() throws Exception {
		mvc.perform(get("/login").param("lang", "de"))
			.andExpect(cookie().doesNotExist(LanguageResolver.COOKIE))
			.andExpect(content().string(containsString("<html lang=\"en\"")));
		mvc.perform(get("/login").cookie(new Cookie(LanguageResolver.COOKIE, "xx")))
			.andExpect(content().string(containsString("<html lang=\"en\"")));
	}

	@Test
	void formErrorsAreTranslated() throws Exception {
		mvc.perform(post("/register").with(csrf()).cookie(CZECH)
				.param("email", "not-an-email")
				.param("displayName", "")
				.param("password", "short")
				.param("confirmPassword", "other"))
			.andExpect(status().isOk())
			.andExpect(content().string(allOf(containsString("Zadejte platnou e-mailovou adresu."),
					containsString("Zadejte jméno, pod kterým vás kamarádi znají."),
					containsString("Použijte aspoň 10 znaků."), containsString("Hesla se neshodují."))));

		mvc.perform(post("/forgot-password").with(csrf()).cookie(CZECH).param("email", "nope"))
			.andExpect(content().string(containsString("Zadejte e-mail, se kterým jste se registrovali.")));
	}

	@Test
	void textsWithArgumentsAndApostrophesComeOutRight() throws Exception {
		// "We''ll" in the properties file must come out as one apostrophe, with the minutes filled in.
		mvc.perform(get("/forgot-password"))
			.andExpect(content().string(containsString("We&#39;ll send you a link to choose a new password. It works once, for 30 minutes.")));
		mvc.perform(get("/forgot-password").cookie(CZECH))
			.andExpect(content().string(containsString("Funguje jednou, 30 minut.")));
	}

	@Test
	void theNotFoundPageIsTranslatedToo() throws Exception {
		mvc.perform(get("/reset-password").param("token", "a".repeat(43)).cookie(CZECH))
			.andExpect(status().isNotFound())
			.andExpect(content().string(containsString("Tento odkaz už nefunguje")));
	}
}
