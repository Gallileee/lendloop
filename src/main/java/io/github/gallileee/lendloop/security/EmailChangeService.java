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

// "Change my email", following the OWASP Authentication Cheat Sheet ("Changing a user's
// registered email address", the variant without MFA):
//
// 1. The user types the new address and the current password. Wrong passwords count like failed
//    logins (LoginThrottle), so an open session on someone else's screen can't move the account.
// 2. The new address is stored as pending (email_change_token), and a one-time link goes to it.
//    The account keeps its old address until that link is used, so a typo can't lock anyone out.
// 3. The old address gets a notice (no link): if the change wasn't the user's, they can still log
//    in with the old address and cancel it in Settings, or reset the password.
// 4. Opening the link (GET shows a button, POST confirms, like /verify-email) changes the address,
//    marks it confirmed and ends every session. Links that went to the old address (password
//    reset, sign-up confirmation) stop working.
//
// OWASP also describes asking the OLD address to confirm too; Lendloop sends it a notice only,
// as Google does, because a user who lost access to the old mailbox must still be able to move.
@Service
public class EmailChangeService {

	private static final Logger log = LoggerFactory.getLogger(EmailChangeService.class);

	static final Duration LINK_VALIDITY = Duration.ofHours(24);

	public enum RequestResult {
		SENT, WRONG_PASSWORD, TOO_MANY_ATTEMPTS, SAME_ADDRESS, TAKEN, TOO_MANY_REQUESTS
	}

	public enum ConfirmResult {
		CHANGED, TAKEN
	}

	public record Confirmation(ConfirmResult result, AppUser user) {
	}

	private final EmailChangeTokenRepository tokens;
	private final EmailVerificationTokenRepository verificationTokens;
	private final PasswordResetTokenRepository resetTokens;
	private final AppUserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final LoginThrottle loginThrottle;
	private final Mailer mailer;
	private final Messages messages;
	private final Clock clock;
	private final AttemptLimiter requestLimits;
	private final String baseUrl;

	public EmailChangeService(EmailChangeTokenRepository tokens, EmailVerificationTokenRepository verificationTokens,
			PasswordResetTokenRepository resetTokens, AppUserRepository users, PasswordEncoder passwordEncoder,
			LoginThrottle loginThrottle, Mailer mailer, Messages messages, Clock clock,
			@Value("${lendloop.base-url}") String baseUrl) {
		this.tokens = tokens;
		this.verificationTokens = verificationTokens;
		this.resetTokens = resetTokens;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.loginThrottle = loginThrottle;
		this.mailer = mailer;
		this.messages = messages;
		this.clock = clock;
		// Each request emails two addresses, one of them typed in by the user: keep it rare.
		this.requestLimits = new AttemptLimiter(3, 10, Duration.ofHours(1), clock);
		this.baseUrl = baseUrl.replaceAll("/+$", "");
	}

	// Throws MailException if an email can't be sent; the pending change is rolled back with it.
	@Transactional
	public RequestResult request(Long userId, String newEmail, String currentPassword, String ip) {
		AppUser user = load(userId);
		if (loginThrottle.blockedFor(user.getEmail(), ip).isPresent()) {
			return RequestResult.TOO_MANY_ATTEMPTS;
		}
		if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			loginThrottle.recordFailure(user.getEmail(), ip);
			return RequestResult.WRONG_PASSWORD;
		}
		String email = RegistrationService.normalizeEmail(newEmail);
		if (email.equals(user.getEmail())) {
			return RequestResult.SAME_ADDRESS;
		}
		// Sign-up already tells anyone whether an address is taken; here it takes a password.
		if (users.existsByEmail(email)) {
			return RequestResult.TAKEN;
		}
		if (requestLimits.blockedFor(user.getEmail(), ip).isPresent()) {
			log.warn("Email change for user {} not sent: too many requests", userId);
			return RequestResult.TOO_MANY_REQUESTS;
		}
		requestLimits.record(user.getEmail(), ip);

		Instant now = clock.instant();
		tokens.deleteExpired(now);
		// Only the newest request counts.
		tokens.deleteAllForUser(userId);
		String token = OneTimeTokens.newToken();
		tokens.save(new EmailChangeToken(user, email, OneTimeTokens.hash(token), now.plus(LINK_VALIDITY), now));

		Locale lang = user.getLocale();
		String greeting = messages.in(lang, "mail.greeting", Mailer.singleLine(user.getDisplayName())) + "\n\n";
		String signature = "\n\n" + messages.in(lang, "mail.signature") + "\n";
		mailer.send(email, messages.in(lang, "mail.emailChange.subject"),
				greeting + messages.in(lang, "mail.emailChange.body", link(token), LINK_VALIDITY.toHours()) + signature,
				null);
		mailer.send(user.getEmail(), messages.in(lang, "mail.emailChange.notice.subject"),
				greeting + messages.in(lang, "mail.emailChange.notice.body", email, baseUrl + "/settings") + signature,
				null);
		log.info("Email change requested by user {}", userId);
		return RequestResult.SENT;
	}

	// The address waiting for confirmation, for the Settings page.
	@Transactional(readOnly = true)
	public Optional<String> pendingFor(Long userId) {
		return tokens.findFirstByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(userId, clock.instant())
			.map(EmailChangeToken::getNewEmail);
	}

	@Transactional
	public void cancel(Long userId) {
		if (tokens.deleteAllForUser(userId) > 0) {
			log.info("Email change cancelled by user {}", userId);
		}
	}

	// The address the link would change to, for the confirmation page; empty if it doesn't work.
	@Transactional(readOnly = true)
	public Optional<String> newEmailFor(String token) {
		return find(token).map(EmailChangeToken::getNewEmail);
	}

	// Empty when the link is unknown, used, cancelled or expired. TAKEN when someone signed up
	// with the new address after the link was sent; the link is used up either way.
	@Transactional
	public Optional<Confirmation> confirm(String token) {
		return find(token).map(change -> {
			AppUser user = change.getUser();
			tokens.deleteAllForUser(user.getId());
			if (users.existsByEmail(change.getNewEmail())) {
				return new Confirmation(ConfirmResult.TAKEN, user);
			}
			user.changeEmail(change.getNewEmail(), clock.instant());
			// They were emailed to the old address.
			resetTokens.deleteAllForUser(user.getId());
			verificationTokens.deleteAllForUser(user.getId());
			log.info("Email changed for user {}", user.getId());
			return new Confirmation(ConfirmResult.CHANGED, user);
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

	private Optional<EmailChangeToken> find(String token) {
		if (!OneTimeTokens.isWellFormed(token)) {
			return Optional.empty();
		}
		return tokens.findByTokenHash(OneTimeTokens.hash(token)).filter(t -> t.isValidAt(clock.instant()));
	}

	private String link(String token) {
		return UriComponentsBuilder.fromUriString(baseUrl)
			.path("/change-email")
			.queryParam("token", token)
			.build()
			.toUriString();
	}

	private AppUser load(Long userId) {
		return users.findById(userId).orElseThrow(() -> new IllegalStateException("No user " + userId));
	}
}
