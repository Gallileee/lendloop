package io.github.gallileee.lendloop.security;

import io.github.gallileee.lendloop.mail.Mailer;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import io.github.gallileee.lendloop.user.RegistrationService;
import io.github.gallileee.lendloop.web.Messages;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

// "Forgot my password": emails a one-time link, then sets the new password.
//
// - The link holds a OneTimeTokens token; only its SHA-256 hash is stored.
// - It works once, for 30 minutes, and asking again replaces the earlier link.
// - Asking for a link always gets the same answer, whether or not the account exists.
// - At most 3 requests per email and 10 per IP an hour, so nobody can flood an inbox.
// - Using the link also confirms the email address: only its owner could read it.
@Service
public class PasswordResetService {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

	static final Duration LINK_VALIDITY = Duration.ofMinutes(30);

	private final PasswordResetTokenRepository tokens;
	private final AppUserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final Mailer mailer;
	private final Messages messages;
	private final LoginThrottle loginThrottle;
	private final Clock clock;
	private final AttemptLimiter requestLimits;
	private final String baseUrl;

	public PasswordResetService(PasswordResetTokenRepository tokens, AppUserRepository users,
			PasswordEncoder passwordEncoder, Mailer mailer, Messages messages, LoginThrottle loginThrottle, Clock clock,
			@Value("${lendloop.base-url}") String baseUrl) {
		this.tokens = tokens;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.mailer = mailer;
		this.messages = messages;
		this.loginThrottle = loginThrottle;
		this.clock = clock;
		this.requestLimits = new AttemptLimiter(3, 10, Duration.ofHours(1), clock);
		this.baseUrl = baseUrl.replaceAll("/+$", "");
	}

	// Sends a link if the account exists and the limits allow it. Says nothing about which
	// happened, so the page can't be used to find out who has an account.
	@Transactional
	public void requestReset(String email, String ip) {
		if (requestLimits.blockedFor(email, ip).isPresent()) {
			log.warn("Password reset request from {} refused: too many requests", ip);
			return;
		}
		requestLimits.record(email, ip);
		Instant now = clock.instant();
		tokens.deleteExpired(now);
		Optional<AppUser> user = users.findByEmail(RegistrationService.normalizeEmail(email));
		if (user.isEmpty()) {
			return;
		}
		// Only the newest link works.
		tokens.deleteAllForUser(user.get().getId());
		String token = OneTimeTokens.newToken();
		tokens.save(new PasswordResetToken(user.get(), OneTimeTokens.hash(token), now.plus(LINK_VALIDITY), now));
		mailer.send(user.get().getEmail(), messages.in(user.get().getLocale(), "mail.reset.subject"), text(user.get(), token), null);
	}

	// Whether the reset page should show the form for this token.
	@Transactional(readOnly = true)
	public boolean isUsable(String token) {
		return find(token).isPresent();
	}

	// Sets the new password and uses up the link. Empty when the link is unknown, used or expired;
	// otherwise the account's email, for the login page.
	@Transactional
	public Optional<String> resetPassword(String token, String newPassword) {
		return find(token).map(resetToken -> {
			AppUser user = resetToken.getUser();
			user.changePasswordHash(passwordEncoder.encode(newPassword));
			user.markEmailVerified(clock.instant());
			tokens.deleteAllForUser(user.getId());
			// Whoever proves they own the inbox can log in straight away.
			loginThrottle.recordSuccess(user.getEmail());
			log.info("Password reset for user {}", user.getId());
			return user.getEmail();
		});
	}

	@Scheduled(fixedDelayString = "${lendloop.login.purge-interval:10m}")
	public void purgeRequestLimits() {
		requestLimits.purgeExpired();
	}

	// For tests: MockMvc requests share one IP.
	public void clearRequestLimits() {
		requestLimits.clear();
	}

	private Optional<PasswordResetToken> find(String token) {
		if (!OneTimeTokens.isWellFormed(token)) {
			return Optional.empty();
		}
		return tokens.findByTokenHash(OneTimeTokens.hash(token)).filter(t -> t.isValidAt(clock.instant()));
	}

	private String text(AppUser user, String token) {
		String link = UriComponentsBuilder.fromUriString(baseUrl)
			.path("/reset-password")
			.queryParam("token", token)
			.build()
			.toUriString();
		Locale lang = user.getLocale();
		return messages.in(lang, "mail.greeting", Mailer.singleLine(user.getDisplayName())) + "\n\n"
				+ messages.in(lang, "mail.reset.body", link, LINK_VALIDITY.toMinutes()) + "\n\n" + messages.in(lang, "mail.signature")
				+ "\n";
	}
}
