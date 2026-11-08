package io.github.gallileee.lendloop.web;

import org.springframework.context.MessageSourceResolvable;

// A request the rules don't allow, explained to the user. It carries a message code (and its
// arguments) instead of a finished sentence, so the controller can show it in the page's
// language: messages.text(e). getMessage() is the code, for logs.
public class RuleException extends RuntimeException implements MessageSourceResolvable {

	private final String code;
	private final Object[] args;

	public RuleException(String code, Object... args) {
		super(code);
		this.code = code;
		this.args = args.clone();
	}

	@Override
	public String[] getCodes() {
		return new String[] {code};
	}

	@Override
	public Object[] getArguments() {
		return args.clone();
	}
}
