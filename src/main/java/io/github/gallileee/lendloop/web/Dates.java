package io.github.gallileee.lendloop.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

// Dates for the pages, used in templates as ${@dates.format(date)}. Always English, like the
// rest of the UI: Thymeleaf's #temporals uses the browser's locale ("17 lis 2026" in a Czech
// browser). "Today" is in the server's time zone until users can choose their own.
@Component("dates")
public class Dates {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

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
}
