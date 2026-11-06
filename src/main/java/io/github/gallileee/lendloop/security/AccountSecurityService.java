package io.github.gallileee.lendloop.security;

import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Password change and "log out everywhere else" for a logged-in user. Both raise the account's
// session version, which ends every other session (SessionVersionFilter); the caller then puts
// the new version into the current session so it stays logged in (CurrentSession).
@Service
public class AccountSecurityService {

	private static final Logger log = LoggerFactory.getLogger(AccountSecurityService.class);

	private final AppUserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final LoginThrottle loginThrottle;

	public AccountSecurityService(AppUserRepository users, PasswordEncoder passwordEncoder,
			LoginThrottle loginThrottle) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.loginThrottle = loginThrottle;
	}

	public enum Result {
		CHANGED, WRONG_PASSWORD, TOO_MANY_ATTEMPTS
	}

	public record PasswordChange(Result result, AppUser user) {
	}

	// The current password is asked for, so an open session left on someone else's screen isn't
	// enough to take the account over. Wrong guesses count like failed logins: the same limits,
	// and the same pause.
	@Transactional
	public PasswordChange changePassword(Long userId, String currentPassword, String newPassword, String ip) {
		AppUser user = load(userId);
		if (loginThrottle.blockedFor(user.getEmail(), ip).isPresent()) {
			return new PasswordChange(Result.TOO_MANY_ATTEMPTS, user);
		}
		if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			loginThrottle.recordFailure(user.getEmail(), ip);
			return new PasswordChange(Result.WRONG_PASSWORD, user);
		}
		user.changePasswordHash(passwordEncoder.encode(newPassword));
		log.info("Password changed by user {}", userId);
		return new PasswordChange(Result.CHANGED, user);
	}

	// E.g. after logging in on a shared computer and forgetting to log out there.
	@Transactional
	public AppUser endOtherSessions(Long userId) {
		AppUser user = load(userId);
		user.endAllSessions();
		return user;
	}

	private AppUser load(Long userId) {
		return users.findById(userId).orElseThrow(() -> new IllegalStateException("No user " + userId));
	}
}
