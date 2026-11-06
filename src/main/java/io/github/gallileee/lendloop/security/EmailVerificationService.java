package io.github.gallileee.lendloop.security;

import io.github.gallileee.lendloop.mail.Mailer;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

// "Confirm your email": after sign-up, a one-time link proves the address belongs to the user.
//
// Until then the account works, but reminders and notices aren't emailed (AppUser.mayBeEmailed),
// so a typo, or someone else's address, doesn't get a stranger our emails. Logging in isn't
// blocked: Lendloop's emails are optional, and a missing email shouldn't lock anyone out.
//
// - The link holds a OneTimeTokens token; only its hash is stored. It works for 48 hours, and
//   sending a new one turns the older one off.
// - Logged-in users can ask for the link again: at most 3 times per account and 10 per IP an hour.
// - A password reset link proves the same thing, so it confirms the address too.
@Service
public class EmailVerificationService {

	private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

	static final Duration LINK_VALIDITY = Duration.ofHours(48);

	public enum ResendResult {
		SENT, ALREADY_VERIFIED, TOO_MANY
	}

	private final EmailVerificationTokenRepository tokens;
	private final AppUserRepository users;
	private final Mailer mailer;
	private final Clock clock;
	private final AttemptLimiter resendLimits;
	private final String baseUrl;

	public EmailVerificationService(EmailVerificationTokenRepository tokens, AppUserRepository users, Mailer mailer,
			Clock clock, @Value("${lendloop.base-url}") String baseUrl) {
		this.tokens = tokens;
		this.users = users;
		this.mailer = mailer;
		this.clock = clock;
		this.resendLimits = new AttemptLimiter(3, 10, Duration.ofHours(1), clock);
		this.baseUrl = baseUrl.replaceAll("/+$", "");
	}

	// Right after sign-up. Throws MailException if the email can't be sent; the account stays,
	// and the user can ask for the link again from Settings.
	@Transactional
	public void sendLink(Long userId) {
		AppUser user = users.findById(userId).orElseThrow();
		if (!user.isEmailVerified()) {
			send(user);
		}
	}

	// "Send the link again" on the Settings page.
	@Transactional
	public ResendResult resend(Long userId, String ip) {
		AppUser user = users.findById(userId).orElseThrow();
		if (user.isEmailVerified()) {
			return ResendResult.ALREADY_VERIFIED;
		}
		if (resendLimits.blockedFor(user.getEmail(), ip).isPresent()) {
			log.warn("Confirmation email for user {} not sent again: too many requests", userId);
			return ResendResult.TOO_MANY;
		}
		resendLimits.record(user.getEmail(), ip);
		send(user);
		return ResendResult.SENT;
	}

	// Whether the confirmation page should show its button for this token.
	@Transactional(readOnly = true)
	public boolean isUsable(String token) {
		return find(token).isPresent();
	}

	// Confirms the address and uses up the link. Empty when the link is unknown, used or expired.
	@Transactional
	public Optional<Long> verify(String token) {
		return find(token).map(verification -> {
			AppUser user = verification.getUser();
			user.markEmailVerified(clock.instant());
			tokens.deleteAllForUser(user.getId());
			log.info("Email confirmed for user {}", user.getId());
			return user.getId();
		});
	}

	@Scheduled(fixedDelayString = "${lendloop.login.purge-interval:10m}")
	public void purgeResendLimits() {
		resendLimits.purgeExpired();
	}

	// For tests: MockMvc requests share one IP.
	public void clearResendLimits() {
		resendLimits.clear();
	}

	private void send(AppUser user) {
		Instant now = clock.instant();
		tokens.deleteExpired(now);
		// Only the newest link works.
		tokens.deleteAllForUser(user.getId());
		String token = OneTimeTokens.newToken();
		tokens.save(new EmailVerificationToken(user, OneTimeTokens.hash(token), now.plus(LINK_VALIDITY), now));
		mailer.send(user.getEmail(), "Confirm your email for Lendloop", text(user, token), null);
	}

	private Optional<EmailVerificationToken> find(String token) {
		if (!OneTimeTokens.isWellFormed(token)) {
			return Optional.empty();
		}
		return tokens.findByTokenHash(OneTimeTokens.hash(token)).filter(t -> t.isValidAt(clock.instant()));
	}

	private String text(AppUser user, String token) {
		String link = UriComponentsBuilder.fromUriString(baseUrl)
			.path("/verify-email")
			.queryParam("token", token)
			.build()
			.toUriString();
		return "Hi " + Mailer.singleLine(user.getDisplayName()) + ",\n\n"
				+ "Welcome to Lendloop! Please confirm that this is your email address:\n\n" + link + "\n\n"
				+ "Until you do, Lendloop won't send you reminders about loans. The link works for "
				+ LINK_VALIDITY.toHours() + " hours. If you didn't sign up, ignore this email: without "
				+ "the confirmation, nothing more is sent to this address.\n\nLendloop\n";
	}
}
