package io.github.gallileee.lendloop.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

// Sends the app's plain-text emails. Spring Boot only creates a JavaMailSender when
// spring.mail.host is set; without one (the local default) the email is written to the log
// instead, so the app runs without a mail server.
@Component
public class Mailer {

	private static final Logger log = LoggerFactory.getLogger(Mailer.class);

	private final ObjectProvider<JavaMailSender> mailSender;
	private final String from;

	public Mailer(ObjectProvider<JavaMailSender> mailSender, @Value("${lendloop.mail.from}") String from) {
		this.mailSender = mailSender;
		this.from = from;
	}

	// `unsubscribeUrl` is for notifications (RFC 8058 headers); null for emails someone asked
	// for, like a password reset, which must not be unsubscribable.
	public void send(String to, String subject, String text, String unsubscribeUrl) throws MailException {
		JavaMailSender sender = mailSender.getIfAvailable();
		if (sender == null) {
			log.info("Mail is not configured (spring.mail.host), so not sending:\nTo: {}\nSubject: {}\n\n{}", to,
					subject, text);
			return;
		}
		sender.send(toMime(sender, to, subject, text, unsubscribeUrl));
	}

	// A MIME message rather than a SimpleMailMessage, because only it can carry extra headers.
	private MimeMessage toMime(JavaMailSender sender, String to, String subject, String text, String unsubscribeUrl) {
		MimeMessage message = sender.createMimeMessage();
		try {
			MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
			helper.setFrom(from);
			helper.setTo(to);
			helper.setSubject(subject);
			helper.setText(text);
			if (unsubscribeUrl != null) {
				// RFC 2369 + RFC 8058: mail apps show their own "Unsubscribe" button, and the
				// provider POSTs "List-Unsubscribe=One-Click" to the link. Gmail and Yahoo require
				// this of bulk senders. (For providers to trust it, the domain must also sign mail
				// with DKIM, which is set up on the mail server, not here.)
				message.setHeader("List-Unsubscribe", "<" + unsubscribeUrl + ">");
				message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
			}
		}
		catch (MessagingException e) {
			throw new MailPreparationException("Could not build the email to " + to, e);
		}
		return message;
	}

	// Names and other user input go into subjects; a control character (e.g. a line break)
	// there could start a new mail header.
	public static String singleLine(String text) {
		return text.replaceAll("\\p{Cntrl}+", " ").strip();
	}
}
