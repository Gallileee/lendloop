package io.github.gallileee.lendloop.loan;

// Published by LoanService when a borrow request is made or answered; RequestEmails tells the
// other side once the change is committed. Only the id travels: the listener loads the loan
// itself, after the publishing transaction has ended.
record RequestEvent(Long loanId, Kind kind) {

	enum Kind {
		// A group member asked for an item: the owner is told.
		REQUESTED,
		// The owner lent it: the borrower is told until when.
		APPROVED,
		// The owner said no: the borrower is told.
		DECLINED
	}
}
