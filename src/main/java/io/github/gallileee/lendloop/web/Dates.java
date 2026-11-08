package io.github.gallileee.lendloop.web;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

// Dates for the pages and emails. format() is always English (emails are English for now). local() follows the page language: "17 Nov 2026" or "17. 11. 2026".
// Thymeleaf's #temporals isn't used because it follows any browser locale, not only the
// languages the pages exist in. "Today" is in the server's time zone until users can choose.
@Component("dates")
public class Dates {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter DAY_CS = DateTimeFormatter.ofPattern("d. M. yyyy", LanguageResolver.CZECH);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter TIME_CS = DateTimeFormatter.ofPattern("d. M. yyyy H:mm", LanguageResolver.CZECH);

	private final Clock clock;

	public Dates(Clock clock) {
		this.clock = clock;
	}

	public LocalDate today() {
		return LocalDate.now(clock.withZone(ZoneId.systemDefault()));
	}

	public String format(LocalDate date) {
		return date == null ? "" : DAY.format(date);
	}

	// In the current request's language; English outside a request (scheduled jobs).
	public String local(LocalDate date) {
		if (date == null) {
			return "";
		}
		return czech() ? DAY_CS.format(date) : DAY.format(date);
	}

	// A moment in the server's time zone: "10 Nov 2026, 14:30" or "10. 11. 2026 14:30".
	public String localDateTime(Instant instant) {
		if (instant == null) {
			return "";
		}
		LocalDateTime time = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
		return czech() ? TIME_CS.format(time) : TIME.format(time);
	}

	private static boolean czech() {
		LocaleContext context = LocaleContextHolder.getLocaleContext();
		return context != null && LanguageResolver.CZECH.equals(context.getLocale());
	}
}
