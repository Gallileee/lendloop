package io.github.gallileee.lendloop.loan;

public enum LoanStatus {
	// The borrower asked; the owner hasn't answered yet.
	REQUESTED,
	// The owner said yes; the borrower has the item until it's returned.
	ACTIVE,
	RETURNED,
	DECLINED,
	// The borrower withdrew the request before the owner answered.
	CANCELLED;

	public boolean isOpen() {
		return this == REQUESTED || this == ACTIVE;
	}
}
