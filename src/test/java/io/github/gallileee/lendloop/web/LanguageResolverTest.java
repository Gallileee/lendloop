package io.github.gallileee.lendloop.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

class LanguageResolverTest {

	@AfterEach
	void reset() {
		LocaleContextHolder.resetLocaleContext();
	}

	@Test
	void picksTheBestSupportedLanguageFromTheHeader() {
		assertThat(LanguageResolver.fromAcceptLanguage(null)).isEqualTo(Locale.ENGLISH);
		assertThat(LanguageResolver.fromAcceptLanguage("cs")).isEqualTo(LanguageResolver.CZECH);
		assertThat(LanguageResolver.fromAcceptLanguage("sk;q=1, cs;q=0.8, en;q=0.9")).isEqualTo(Locale.ENGLISH);
		assertThat(LanguageResolver.fromAcceptLanguage("pl")).isEqualTo(Locale.ENGLISH);
		assertThat(LanguageResolver.fromAcceptLanguage(";;;q=bad")).isEqualTo(Locale.ENGLISH);
	}

	@Test
	void onlySupportedLanguagesAreAccepted() {
		assertThat(LanguageResolver.supported("cs-CZ")).isEqualTo(LanguageResolver.CZECH);
		assertThat(LanguageResolver.supported(" en ")).isEqualTo(Locale.ENGLISH);
		assertThat(LanguageResolver.supported("de")).isNull();
		assertThat(LanguageResolver.supported("")).isNull();
		assertThat(LanguageResolver.supported(null)).isNull();
	}

	@Test
	void datesFollowThePageLanguageButStayEnglishOutsideARequest() {
		Dates dates = new Dates(java.time.Clock.systemUTC());
		LocalDate day = LocalDate.of(2026, 11, 17);

		assertThat(dates.local(day)).isEqualTo("17 Nov 2026");
		LocaleContextHolder.setLocale(LanguageResolver.CZECH);
		assertThat(dates.local(day)).isEqualTo("17. 11. 2026");
		assertThat(dates.format(day)).as("not the page language").isEqualTo("17 Nov 2026");
		assertThat(dates.format(day, LanguageResolver.CZECH)).as("an email's own language").isEqualTo("17. 11. 2026");
	}
}
