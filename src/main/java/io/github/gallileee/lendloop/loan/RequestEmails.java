package io.github.gallileee.lendloop.loan;

import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.EmailList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

// Emails the other side of a borrow request: the owner when someone asks, the borrower when the
// owner answers. Withdrawing a request sends nothing (the owner's list just gets shorter).
//
// Runs after the request's transaction has committed (TransactionalEventListener's default
// phase), so a request that is rolled back (e.g. it lost a race to a unique constraint) never
// sends an email, and a mail server that is down never undoes the request: the failure is only
// logged, and the change is still on the Loans page. Not retried, like the owner's overdue notice.
//
// It runs in the same request thread, so the page waits for the mail server.
@Component
class RequestEmails {

	private static final Logger log = LoggerFactory.getLogger(RequestEmails.class);

	private final LoanRepository loans;
	private final ReminderMailer mailer;

	RequestEmails(LoanRepository loans, ReminderMailer mailer) {
		this.loans = loans;
		this.mailer = mailer;
	}

	// A new read-only transaction, because the publishing one is over by now.
	@TransactionalEventListener
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	void send(RequestEvent event) {
		Loan loan = loans.findWithParties(event.loanId()).orElse(null);
		if (loan == null || loan.getStatus() != expectedStatus(event.kind())) {
			// Deleted or changed again in the meantime: the email would be out of date.
			return;
		}
		AppUser to = event.kind() == RequestEvent.Kind.REQUESTED ? loan.getItem().getOwner() : loan.getBorrower();
		if (to == null || !to.mayBeEmailed(EmailList.REQUESTS)) {
			return;
		}
		try {
			mailer.sendRequestEmail(loan, event.kind());
		}
		catch (MailException e) {
			log.warn("Could not email user {} about loan {} ({})", to.getId(), loan.getId(), event.kind(), e);
		}
	}

	private static LoanStatus expectedStatus(RequestEvent.Kind kind) {
		return switch (kind) {
			case REQUESTED -> LoanStatus.REQUESTED;
			case APPROVED -> LoanStatus.ACTIVE;
			case DECLINED -> LoanStatus.DECLINED;
		};
	}
}
