package io.github.gallileee.lendloop.loan;

import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.EmailList;
import io.github.gallileee.lendloop.user.UnsubscribeLinks;
import io.github.gallileee.lendloop.web.Dates;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

// Writes and sends the reminder emails. Spring Boot only creates a JavaMailSender when
// spring.mail.host is set; without one (the local default) the email is written to the log
// instead, so the app runs without a mail server.
@Component
class ReminderMailer {

	private static final Logger log = LoggerFactory.getLogger(ReminderMailer.class);

	private final ObjectProvider<JavaMailSender> mailSender;
	private final Dates dates;
	private final UnsubscribeLinks unsubscribeLinks;
	private final String from;
	private final String loansUrl;
	private final String settingsUrl;

	ReminderMailer(ObjectProvider<JavaMailSender> mailSender, Dates dates, UnsubscribeLinks unsubscribeLinks,
			@Value("${lendloop.mail.from}") String from, @Value("${lendloop.base-url}") String baseUrl) {
		this.mailSender = mailSender;
		this.dates = dates;
		this.unsubscribeLinks = unsubscribeLinks;
		this.from = from;
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
		JavaMailSender sender = mailSender.getIfAvailable();
		if (sender == null) {
			log.info("Mail is not configured (spring.mail.host), so not sending:\nTo: {}\nSubject: {}\n\n{}",
					mail.to(), mail.subject(), mail.text());
			return;
		}
		sender.send(toMime(sender, mail));
	}

	// A MIME message rather than a SimpleMailMessage, because only it can carry extra headers.
	private MimeMessage toMime(JavaMailSender sender, Email mail) {
		MimeMessage message = sender.createMimeMessage();
		try {
			MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
			helper.setFrom(from);
			helper.setTo(mail.to());
			helper.setSubject(mail.subject());
			helper.setText(mail.text());
			// RFC 2369 + RFC 8058: mail apps show their own "Unsubscribe" button, and the provider
			// POSTs "List-Unsubscribe=One-Click" to the link. Gmail and Yahoo require this of bulk
			// senders. (For providers to trust it, the domain must also sign mail with DKIM, which
			// is set up on the mail server, not here.)
			message.setHeader("List-Unsubscribe", "<" + mail.unsubscribeUrl() + ">");
			message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
		}
		catch (MessagingException e) {
			throw new MailPreparationException("Could not build the email to " + mail.to(), e);
		}
		return message;
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
		return text.replaceAll("\\p{Cntrl}+", " ").strip();
	}
}
