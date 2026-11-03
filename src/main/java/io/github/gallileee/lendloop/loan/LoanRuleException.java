package io.github.gallileee.lendloop.loan;

// A request the rules don't allow, e.g. approving a loan that was already cancelled.
// The message is written for the user and shown on the page.
public class LoanRuleException extends RuntimeException {

	public LoanRuleException(String userMessage) {
		super(userMessage);
	}
}
