package io.github.gallileee.lendloop.loan;

import io.github.gallileee.lendloop.mail.Mailer;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.EmailList;
import io.github.gallileee.lendloop.user.UnsubscribeLinks;
import io.github.gallileee.lendloop.web.Dates;
import io.github.gallileee.lendloop.web.Messages;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

// Writes the reminder emails, in the recipient's language; Mailer sends them (or logs them when no mail server is set).
@Component
class ReminderMailer {

	private final Mailer mailer;
	private final Dates dates;
	private final Messages messages;
	private final UnsubscribeLinks unsubscribeLinks;
	private final String loansUrl;
	private final String settingsUrl;

	ReminderMailer(Mailer mailer, Dates dates, Messages messages, UnsubscribeLinks unsubscribeLinks,
			@Value("${lendloop.base-url}") String baseUrl) {
		this.mailer = mailer;
		this.dates = dates;
		this.messages = messages;
		this.unsubscribeLinks = unsubscribeLinks;
		String base = baseUrl.replaceAll("/+$", "");
		this.loansUrl = base + "/loans";
		this.settingsUrl = base + "/settings";
	}

	// One email, before it becomes a MIME message. `unsubscribeUrl` turns off exactly this kind of
	// email, for exactly this recipient.
	record Email(String to, String subject, String text, String unsubscribeUrl) {
	}

	void send(Loan loan, ReminderKind kind, LocalDate today) throws MailException {
		deliver(write(loan, kind, today));
	}

	void sendOwnerNotice(Loan loan, LocalDate today) throws MailException {
		deliver(writeOwnerNotice(loan, today));
	}

	void sendOwnerReminder(Loan loan, ReminderKind kind, LocalDate today) throws MailException {
		deliver(writeOwnerReminder(loan, kind, today));
	}

	private void deliver(Email mail) {
		mailer.send(mail.to(), mail.subject(), mail.text(), mail.unsubscribeUrl());
	}

	Email write(Loan loan, ReminderKind kind, LocalDate today) {
		AppUser to = loan.getBorrower();
		Locale lang = to.getLocale();
		// Item and user names are user input; control characters (e.g. a line break) in a
		// subject could start a new mail header.
		String item = singleLine(loan.getItem().getName());
		String owner = singleLine(loan.getItem().getOwner().getDisplayName());
		String due = dates.format(loan.getDueDate(), lang);

		String subject;
		String body;
		if (kind == ReminderKind.DUE_TOMORROW) {
			subject = messages.in(lang, "mail.dueTomorrow.subject", item);
			body = messages.in(lang, "mail.dueTomorrow.body", item, owner, due);
		}
		else {
			subject = messages.in(lang, "mail.overdue.subject", item, due);
			body = messages.in(lang, "mail.overdue.body", item, owner, due, daysLate(loan, today, lang));
		}
		return mail(to, EmailList.BORROWED, subject, body);
	}

	Email writeOwnerNotice(Loan loan, LocalDate today) {
		AppUser to = loan.getItem().getOwner();
		Locale lang = to.getLocale();
		String item = singleLine(loan.getItem().getName());
		String borrower = singleLine(loan.getBorrowerDisplayName());
		String due = dates.format(loan.getDueDate(), lang);
		String subject = messages.in(lang, "mail.ownerOverdue.subject", item, borrower);
		String body = messages.in(lang, "mail.ownerNotice.body", item, borrower, due, daysLate(loan, today, lang));
		return mail(to, EmailList.LENT, subject, body);
	}

	// For a loan to someone outside the app: the reminder the borrower would get, sent to the
	// owner instead, because only the owner can pass it on.
	Email writeOwnerReminder(Loan loan, ReminderKind kind, LocalDate today) {
		AppUser to = loan.getItem().getOwner();
		Locale lang = to.getLocale();
		String item = singleLine(loan.getItem().getName());
		String borrower = singleLine(loan.getBorrowerDisplayName());
		String due = dates.format(loan.getDueDate(), lang);

		String subject;
		String body;
		if (kind == ReminderKind.DUE_TOMORROW) {
			subject = messages.in(lang, "mail.ownerReminder.dueTomorrow.subject", item, borrower);
			body = messages.in(lang, "mail.ownerReminder.dueTomorrow.body", item, borrower, due);
		}
		else {
			subject = messages.in(lang, "mail.ownerOverdue.subject", item, borrower);
			body = messages.in(lang, "mail.ownerReminder.overdue.body", item, borrower, due,
					daysLate(loan, today, lang));
		}
		return mail(to, EmailList.LENT, subject, body);
	}

	// Every email ends with a one-click link that turns off this kind of email, and a link to
	// the Settings page.
	private Email mail(AppUser to, EmailList list, String subject, String body) {
		Locale lang = to.getLocale();
		String unsubscribeUrl = unsubscribeLinks.urlFor(to.getId(), list);
		String text = messages.in(lang, "mail.greeting", singleLine(to.getDisplayName())) + "\n\n" + body + "\n\n"
				+ messages.in(lang, "mail.loansLink", loansUrl) + "\n\n" + messages.in(lang, "mail.signature")
				+ "\n--\n"
				+ messages.in(lang, "mail.footer", messages.in(lang, list.getMessageCode()), unsubscribeUrl,
						settingsUrl)
				+ "\n";
		return new Email(to.getEmail(), subject, text, unsubscribeUrl);
	}

	private String daysLate(Loan loan, LocalDate today, Locale lang) {
		return messages.in(lang, "mail.daysAgo", ChronoUnit.DAYS.between(loan.getDueDate(), today));
	}

	private static String singleLine(String text) {
		return Mailer.singleLine(text);
	}
}
