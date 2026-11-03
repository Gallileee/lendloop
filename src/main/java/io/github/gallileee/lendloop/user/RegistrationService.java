package io.github.gallileee.lendloop.user;

import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

	private final AppUserRepository users;
	private final PasswordEncoder passwordEncoder;

	public RegistrationService(AppUserRepository users, PasswordEncoder passwordEncoder) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
	}

	// Emails are compared case-insensitively, so they are stored trimmed and lower-cased.
	public static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	@Transactional
	public AppUser register(RegistrationForm form) {
		String email = normalizeEmail(form.getEmail());
		if (users.existsByEmail(email)) {
			throw new EmailAlreadyRegisteredException(email);
		}
		AppUser user = new AppUser(email, form.getDisplayName().trim(), passwordEncoder.encode(form.getPassword()));
		try {
			return users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException e) {
			// Two sign-ups with the same email at once: the unique constraint catches the second.
			throw new EmailAlreadyRegisteredException(email);
		}
	}
}
