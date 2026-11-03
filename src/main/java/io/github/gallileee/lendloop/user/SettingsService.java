package io.github.gallileee.lendloop.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {

	private final AppUserRepository users;

	public SettingsService(AppUserRepository users) {
		this.users = users;
	}

	@Transactional(readOnly = true)
	public SettingsForm formFor(Long userId) {
		return SettingsForm.of(load(userId));
	}

	// Returns the saved user, so the caller can refresh the logged-in principal's name.
	@Transactional
	public AppUser save(Long userId, SettingsForm form) {
		AppUser user = load(userId);
		user.changeSettings(form.getDisplayName().trim(), form.isRemindBorrowed(), form.isNotifyLentOverdue());
		return user;
	}

	// The id comes from the session, so a missing user means the account is gone.
	private AppUser load(Long userId) {
		return users.findById(userId).orElseThrow(() -> new IllegalStateException("No user " + userId));
	}
}
