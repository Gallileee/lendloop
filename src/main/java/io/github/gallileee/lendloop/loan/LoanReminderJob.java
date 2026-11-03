package io.github.gallileee.lendloop.loan;

import io.github.gallileee.lendloop.web.Dates;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Once a day, emails borrowers whose loan is due tomorrow or overdue (rules in
// Loan.reminderDueOn). Each sent email is recorded on the loan right away, so a crash halfway
// through or a second run the same day doesn't send it twice. A failed email is not recorded
// and is tried again on the next run; the other loans still get theirs.
//
// Assumes one running instance of the app: two instances would both send.
@Component
class LoanReminderJob {

	private static final Logger log = LoggerFactory.getLogger(LoanReminderJob.class);

	private final LoanRepository loans;
	private final ReminderMailer mailer;
	private final Dates dates;

	LoanReminderJob(LoanRepository loans, ReminderMailer mailer, Dates dates) {
		this.loans = loans;
		this.mailer = mailer;
		this.dates = dates;
	}

	// "-" turns it off (the tests do, and call sendReminders themselves).
	@Scheduled(cron = "${lendloop.reminders.cron}")
	void runDaily() {
		sendReminders(dates.today());
	}

	// Returns how many emails were sent.
	int sendReminders(LocalDate today) {
		int sent = 0;
		for (Loan loan : loans.findActiveDueBy(today.plusDays(1))) {
			ReminderKind kind = loan.reminderDueOn(today).orElse(null);
			if (kind == null) {
				continue;
			}
			try {
				mailer.send(loan, kind, today);
			}
			catch (MailException ex) {
				log.warn("Could not send the {} reminder for loan {}: {}", kind, loan.getId(), ex.getMessage());
				continue;
			}
			loans.recordReminder(loan.getId(), today);
			sent++;
		}
		log.info("Sent {} loan reminder(s) for {}", sent, today);
		return sent;
	}
}
