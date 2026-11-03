package io.github.gallileee.lendloop.loan;

import io.github.gallileee.lendloop.web.Dates;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

// Writes and sends the reminder emails. Spring Boot only creates a JavaMailSender when
// spring.mail.host is set; without one (the local default) the email is written to the log
// instead, so the app runs without a mail server.
@Component
class ReminderMailer {

	private static final Logger log = LoggerFactory.getLogger(ReminderMailer.class);

	private final ObjectProvider<JavaMailSender> mailSender;
	private final Dates dates;
	private final String from;
	private final String loansUrl;
	private final String settingsUrl;

	ReminderMailer(ObjectProvider<JavaMailSender> mailSender, Dates dates,
			@Value("${lendloop.mail.from}") String from, @Value("${lendloop.base-url}") String baseUrl) {
		this.mailSender = mailSender;
		this.dates = dates;
		this.from = from;
		String base = baseUrl.replaceAll("/+$", "");
		this.loansUrl = base + "/loans";
		this.settingsUrl = base + "/settings";
	}

	void send(Loan loan, ReminderKind kind, LocalDate today) throws MailException {
		deliver(write(loan, kind, today));
	}

	void sendOwnerNotice(Loan loan, LocalDate today) throws MailException {
		deliver(writeOwnerNotice(loan, today));
	}

	private void deliver(SimpleMailMessage mail) {
		JavaMailSender sender = mailSender.getIfAvailable();
		if (sender == null) {
			log.info("Mail is not configured (spring.mail.host), so not sending:\nTo: {}\nSubject: {}\n\n{}",
					mail.getTo()[0], mail.getSubject(), mail.getText());
			return;
		}
		sender.send(mail);
	}

	SimpleMailMessage write(Loan loan, ReminderKind kind, LocalDate today) {
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
		return mail(loan.getBorrower().getEmail(), loan.getBorrower().getDisplayName(), subject, body);
	}

	SimpleMailMessage writeOwnerNotice(Loan loan, LocalDate today) {
		String item = quotedItem(loan);
		String borrower = singleLine(loan.getBorrower().getDisplayName());
		String due = dates.format(loan.getDueDate());
		String subject = item + " is overdue at " + borrower;
		String body = item + ", which you lent to " + borrower + ", was due back on " + due + " ("
				+ daysLate(loan, today) + "). You may want to ask " + borrower
				+ " about it. When you have it back, mark it as returned.";
		return mail(loan.getItem().getOwner().getEmail(), loan.getItem().getOwner().getDisplayName(), subject, body);
	}

	// Every email links to the Settings page, where each kind can be turned off.
	private SimpleMailMessage mail(String to, String name, String subject, String body) {
		SimpleMailMessage mail = new SimpleMailMessage();
		mail.setFrom(from);
		mail.setTo(to);
		mail.setSubject(subject);
		mail.setText("Hi " + singleLine(name) + ",\n\n" + body + "\n\nYour loans: " + loansUrl
				+ "\n\nLendloop\n--\nDon't want these emails? Turn them off in your settings: " + settingsUrl + "\n");
		return mail;
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
