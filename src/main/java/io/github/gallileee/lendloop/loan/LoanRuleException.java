package io.github.gallileee.lendloop.loan;

import io.github.gallileee.lendloop.web.RuleException;

// A request the rules don't allow, e.g. approving a loan that was already cancelled.
// It carries a message code (see RuleException), shown to the user as a flash error.
public class LoanRuleException extends RuleException {

	public LoanRuleException(String code, Object... args) {
		super(code, args);
	}
}
