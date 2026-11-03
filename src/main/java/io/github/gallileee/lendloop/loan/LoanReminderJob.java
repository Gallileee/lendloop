package io.github.gallileee.lendloop.loan;

import io.github.gallileee.lendloop.web.Dates;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Once a day, emails borrowers whose loan is due tomorrow or overdue (rules in
// Loan.reminderDueOn), and owners on the first day their item is overdue (Loan.ownerNoticeDueOn).
// Each user chooses which of these they get on the Settings page.
//
// Each handled loan is recorded right away, so a crash halfway through or a second run the
// same day doesn't send it twice. A failed borrower email is not recorded and is tried again
// on the next run; the other loans still get theirs. A failed owner email is only logged:
// retrying it would send the borrower's reminder twice.
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
			if (loan.getBorrower().isRemindBorrowed()) {
				try {
					mailer.send(loan, kind, today);
					sent++;
				}
				catch (MailException ex) {
					log.warn("Could not send the {} reminder for loan {}: {}", kind, loan.getId(), ex.getMessage());
					continue;
				}
			}
			if (loan.ownerNoticeDueOn(today) && loan.getItem().getOwner().isNotifyLentOverdue()) {
				try {
					mailer.sendOwnerNotice(loan, today);
					sent++;
				}
				catch (MailException ex) {
					log.warn("Could not tell the owner that loan {} is overdue: {}", loan.getId(), ex.getMessage());
				}
			}
			// Recorded even if nobody wanted an email, so the owner notice stays a one-off.
			loans.recordReminder(loan.getId(), today);
		}
		log.info("Sent {} loan email(s) for {}", sent, today);
		return sent;
	}
}
