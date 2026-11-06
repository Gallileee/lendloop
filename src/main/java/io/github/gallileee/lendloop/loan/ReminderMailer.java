package io.github.gallileee.lendloop.loan;

import io.github.gallileee.lendloop.mail.Mailer;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.EmailList;
import io.github.gallileee.lendloop.user.UnsubscribeLinks;
import io.github.gallileee.lendloop.web.Dates;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

// Writes the reminder emails; Mailer sends them (or logs them when no mail server is set).
@Component
class ReminderMailer {

	private final Mailer mailer;
	private final Dates dates;
	private final UnsubscribeLinks unsubscribeLinks;
	private final String loansUrl;
	private final String settingsUrl;

	ReminderMailer(Mailer mailer, Dates dates, UnsubscribeLinks unsubscribeLinks,
			@Value("${lendloop.base-url}") String baseUrl) {
		this.mailer = mailer;
		this.dates = dates;
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
		// Item and user names are user input; control characters (e.g. a line break) in a
		// subject could start a new mail header.
		String item = quotedItem(loan);
		String owner = singleLine(loan.getItem().getOwner().getDisplayName());
		String due = dates.format(loan.getDueDate());

		String subject;
		String body;
		if (kind == ReminderKind.DUE_TOMORROW) {
			subject = item + " is due back tomorrow";
			body = item + ", which you borrowed from " + owner + ", is due back tomorrow, " + due + ".";
		}
		else {
			subject = item + " was due back on " + due;
			body = item + ", which you borrowed from " + owner + ", was due back on " + due + " ("
					+ daysLate(loan, today) + "). Please give it back to " + owner
					+ " or let them know when you will.";
		}
		return mail(loan.getBorrower(), EmailList.BORROWED, subject, body);
	}

	Email writeOwnerNotice(Loan loan, LocalDate today) {
		String item = quotedItem(loan);
		String borrower = singleLine(loan.getBorrowerDisplayName());
		String due = dates.format(loan.getDueDate());
		String subject = item + " is overdue at " + borrower;
		String body = item + ", which you lent to " + borrower + ", was due back on " + due + " ("
				+ daysLate(loan, today) + "). You may want to ask " + borrower
				+ " about it. When you have it back, mark it as returned.";
		return mail(loan.getItem().getOwner(), EmailList.LENT, subject, body);
	}

	// For a loan to someone outside the app: the reminder the borrower would get, sent to the
	// owner instead, because only the owner can pass it on.
	Email writeOwnerReminder(Loan loan, ReminderKind kind, LocalDate today) {
		String item = quotedItem(loan);
		String borrower = singleLine(loan.getBorrowerDisplayName());
		String due = dates.format(loan.getDueDate());
		String why = borrower + " isn't on Lendloop, so this reminder comes to you.";

		String subject;
		String body;
		if (kind == ReminderKind.DUE_TOMORROW) {
			subject = item + " is due back from " + borrower + " tomorrow";
			body = item + ", which you lent to " + borrower + ", is due back tomorrow, " + due + ". " + why
					+ " When you have it back, mark it as returned.";
		}
		else {
			subject = item + " is overdue at " + borrower;
			body = item + ", which you lent to " + borrower + ", was due back on " + due + " ("
					+ daysLate(loan, today) + "). " + why + " You may want to ask " + borrower
					+ " about it. Until it's marked as returned, you'll get this once a week.";
		}
		return mail(loan.getItem().getOwner(), EmailList.LENT, subject, body);
	}

	// Every email ends with a one-click link that turns off this kind of email, and a link to
	// the Settings page.
	private Email mail(AppUser to, EmailList list, String subject, String body) {
		String unsubscribeUrl = unsubscribeLinks.urlFor(to.getId(), list);
		String text = "Hi " + singleLine(to.getDisplayName()) + ",\n\n" + body + "\n\nYour loans: " + loansUrl
				+ "\n\nLendloop\n--\nYou get this because " + list.getDescription() + " are on.\nStop them: "
				+ unsubscribeUrl + "\nAll email settings: " + settingsUrl + "\n";
		return new Email(to.getEmail(), subject, text, unsubscribeUrl);
	}

	private static String quotedItem(Loan loan) {
		return "“" + singleLine(loan.getItem().getName()) + "”";
	}

	private static String daysLate(Loan loan, LocalDate today) {
		long days = ChronoUnit.DAYS.between(loan.getDueDate(), today);
		return days + (days == 1 ? " day" : " days") + " ago";
	}

	private static String singleLine(String text) {
		return Mailer.singleLine(text);
	}
}
