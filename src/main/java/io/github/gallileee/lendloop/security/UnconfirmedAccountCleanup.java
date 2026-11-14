package io.github.gallileee.lendloop.security;

import io.github.gallileee.lendloop.mail.Mailer;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import io.github.gallileee.lendloop.web.Dates;
import io.github.gallileee.lendloop.web.Messages;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// Deletes accounts whose email address was never confirmed and that nobody uses, so an address
// typed by mistake (or someone else's) doesn't stay taken forever. "Nobody uses" means no items,
// no groups and no loans as a borrower (AppUserRepository.UNUSED_AND_UNCONFIRMED): an account
// someone has put something into is kept, even unconfirmed, and keeps its banner in Settings.
//
// Once a day:
// 1. A warned account that has been confirmed or used since loses its warning.
// 2. Accounts older than delete-after minus warning-period get one email: confirm (with a new
//    link) or the account will be deleted. A failed email is tried again on the next run.
// 3. Accounts older than delete-after that were warned at least warning-period ago are deleted,
//    so nobody is deleted without the full warning period, even if the job was off for a while.
//
// Assumes one running instance of the app, like LoanReminderJob.
@Component
public class UnconfirmedAccountCleanup {

	private static final Logger log = LoggerFactory.getLogger(UnconfirmedAccountCleanup.class);

	public record Result(int warned, int deleted) {
	}

	private final AppUserRepository users;
	private final EmailVerificationService verifications;
	private final Mailer mailer;
	private final Messages messages;
	private final Dates dates;
	private final Clock clock;
	private final TransactionTemplate transactions;
	private final Duration deleteAfter;
	private final Duration warningPeriod;

	public UnconfirmedAccountCleanup(AppUserRepository users, EmailVerificationService verifications, Mailer mailer,
			Messages messages, Dates dates, Clock clock, TransactionTemplate transactions,
			@Value("${lendloop.unconfirmed.delete-after:30d}") Duration deleteAfter,
			@Value("${lendloop.unconfirmed.warning-period:7d}") Duration warningPeriod) {
		if (warningPeriod.isNegative() || warningPeriod.isZero() || warningPeriod.compareTo(deleteAfter) >= 0) {
			throw new IllegalArgumentException("lendloop.unconfirmed.warning-period must be positive and shorter than"
					+ " lendloop.unconfirmed.delete-after, got " + warningPeriod + " and " + deleteAfter);
		}
		if (warningPeriod.compareTo(EmailVerificationService.LINK_VALIDITY) < 0) {
			log.warn("The deletion warning period ({}) is shorter than the confirmation link's validity", warningPeriod);
		}
		this.users = users;
		this.verifications = verifications;
		this.mailer = mailer;
		this.messages = messages;
		this.dates = dates;
		this.clock = clock;
		this.transactions = transactions;
		this.deleteAfter = deleteAfter;
		this.warningPeriod = warningPeriod;
	}

	// "-" turns it off (the tests do, and call run themselves).
	@Scheduled(cron = "${lendloop.unconfirmed.cron:0 30 3 * * *}")
	void runDaily() {
		run(clock.instant());
	}

	public Result run(Instant now) {
		Integer forgotten = transactions.execute(status -> users.forgetWarningsOfUsedAccounts());
		if (forgotten != null && forgotten > 0) {
			log.info("{} unconfirmed account(s) are in use now and will not be deleted", forgotten);
		}

		int warned = 0;
		for (Long id : users.findUnusedToWarn(now.minus(deleteAfter).plus(warningPeriod))) {
			try {
				transactions.executeWithoutResult(status -> warn(id, now));
				warned++;
			}
			catch (MailException ex) {
				// Rolled back: no token, no warning recorded, so the next run tries again.
				log.warn("Could not warn unconfirmed user {} about the deletion: {}", id, ex.getMessage());
			}
		}

		int deleted = 0;
		for (Long id : users.findUnusedToDelete(now.minus(deleteAfter), now.minus(warningPeriod))) {
			Integer count = transactions.execute(status -> users.deleteIfUnused(id));
			if (count != null && count > 0) {
				log.info("Deleted unconfirmed, unused user {}", id);
				deleted += count;
			}
		}
		if (warned > 0 || deleted > 0) {
			log.info("Unconfirmed accounts: warned {}, deleted {}", warned, deleted);
		}
		return new Result(warned, deleted);
	}

	private void warn(Long id, Instant now) {
		AppUser user = users.findById(id).orElseThrow();
		String link = verifications.newLink(user);
		user.markDeletionWarned(now);
		mailer.send(user.getEmail(), messages.in(user.getLocale(), "mail.unconfirmed.subject"),
				text(user, link, deletionDate(user, now)), null);
	}

	// The first day the account can be deleted: delete-after from sign-up, but never before the
	// whole warning period has passed.
	LocalDate deletionDate(AppUser user, Instant now) {
		Instant byAge = user.getCreatedAt().plus(deleteAfter);
		Instant byWarning = now.plus(warningPeriod);
		Instant when = byAge.isAfter(byWarning) ? byAge : byWarning;
		return LocalDate.ofInstant(when, ZoneId.systemDefault());
	}

	private String text(AppUser user, String link, LocalDate deleteOn) {
		Locale lang = user.getLocale();
		LocalDate signedUp = LocalDate.ofInstant(user.getCreatedAt(), ZoneId.systemDefault());
		return messages.in(lang, "mail.greeting", Mailer.singleLine(user.getDisplayName())) + "\n\n"
				+ messages.in(lang, "mail.unconfirmed.body", dates.format(signedUp, lang), link,
						EmailVerificationService.LINK_VALIDITY.toHours(), dates.format(deleteOn, lang))
				+ "\n\n" + messages.in(lang, "mail.signature") + "\n";
	}
}
