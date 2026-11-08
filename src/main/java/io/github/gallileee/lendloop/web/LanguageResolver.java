package io.github.gallileee.lendloop.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.servlet.LocaleResolver;

// Picks the page language: the one chosen with the switcher (a cookie), otherwise the best match
// for the browser's Accept-Language header, otherwise English. Only supported languages ever come
// out, so a hand-made cookie or header can't put e.g. "de" into <html lang>, and the server's own
// default locale (Czech on a Czech machine) never leaks in.
public class LanguageResolver implements LocaleResolver {

	public static final Locale ENGLISH = Locale.ENGLISH;
	public static final Locale CZECH = Locale.forLanguageTag("cs");
	public static final List<Locale> SUPPORTED = List.of(ENGLISH, CZECH);
	public static final String COOKIE = "lendloop-lang";

	private static final Duration COOKIE_AGE = Duration.ofDays(365);
	// Set when the language changes during a request, so that same response already uses it.
	private static final String CHOSEN = LanguageResolver.class.getName() + ".CHOSEN";

	@Override
	public Locale resolveLocale(HttpServletRequest request) {
		if (request.getAttribute(CHOSEN) instanceof Locale chosen) {
			return chosen;
		}
		Locale fromCookie = supported(cookieValue(request));
		return fromCookie != null ? fromCookie : fromAcceptLanguage(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
	}

	@Override
	public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
		Locale language = locale == null ? null : supported(locale.getLanguage());
		if (language == null) {
			return;
		}
		request.setAttribute(CHOSEN, language);
		String path = request.getContextPath().isEmpty() ? "/" : request.getContextPath();
		ResponseCookie cookie = ResponseCookie.from(COOKIE, language.getLanguage())
			.path(path)
			.maxAge(COOKIE_AGE)
			.httpOnly(true)
			.secure(request.isSecure())
			.sameSite("Lax")
			.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	// The supported language for a tag like "cs" or "cs-CZ", or null.
	public static Locale supported(String tag) {
		if (tag == null || tag.isBlank()) {
			return null;
		}
		String language = Locale.forLanguageTag(tag.strip()).getLanguage();
		return SUPPORTED.stream().filter(l -> l.getLanguage().equals(language)).findFirst().orElse(null);
	}

	static Locale fromAcceptLanguage(String header) {
		if (header == null || header.isBlank()) {
			return ENGLISH;
		}
		try {
			Locale match = Locale.lookup(Locale.LanguageRange.parse(header), SUPPORTED);
			return match != null ? match : ENGLISH;
		}
		catch (IllegalArgumentException malformed) {
			return ENGLISH;
		}
	}

	private static String cookieValue(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie c : cookies) {
			if (COOKIE.equals(c.getName())) {
				return c.getValue();
			}
		}
		return null;
	}
}
