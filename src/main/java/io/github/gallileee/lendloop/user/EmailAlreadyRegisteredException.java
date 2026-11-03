package io.github.gallileee.lendloop.user;

public class EmailAlreadyRegisteredException extends RuntimeException {

	public EmailAlreadyRegisteredException(String email) {
		super("Email already registered: " + email);
	}
}
