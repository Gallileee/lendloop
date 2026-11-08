package io.github.gallileee.lendloop.web;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

// Texts from messages*.properties. text(): in the current request's language, for the flash
// messages controllers put on the next page (resolved when the action runs, which is the same
// browser, and so the same language, that shows the next page). in(): in a given language, for
// emails, which are written in the recipient's language, not the sender's.
@Component
public class Messages {

	private final MessageSource source;

	public Messages(MessageSource source) {
		this.source = source;
	}

	public String text(String code, Object... args) {
		return source.getMessage(code, args, LocaleContextHolder.getLocale());
	}

	public String in(Locale locale, String code, Object... args) {
		return source.getMessage(code, args, locale);
	}

	public String text(MessageSourceResolvable resolvable) {
		return source.getMessage(resolvable, LocaleContextHolder.getLocale());
	}
}
