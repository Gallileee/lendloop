package io.github.gallileee.lendloop.user;

import io.github.gallileee.lendloop.security.ChangeEmailForm;
import io.github.gallileee.lendloop.security.ChangePasswordForm;
import io.github.gallileee.lendloop.security.EmailChangeService;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

// What the Settings page needs besides the form that was just posted. Several controllers render
// the page (settings, password, email change), each after a failed post of its own form, so each
// fills in the others here and keeps its own, with its errors.
@Component
public class SettingsPage {

	private final SettingsService settingsService;
	private final EmailChangeService emailChanges;

	public SettingsPage(SettingsService settingsService, EmailChangeService emailChanges) {
		this.settingsService = settingsService;
		this.emailChanges = emailChanges;
	}

	public void prepare(Long userId, Model model) {
		if (!model.containsAttribute("form")) {
			model.addAttribute("form", settingsService.formFor(userId));
		}
		if (!model.containsAttribute("passwordForm")) {
			model.addAttribute("passwordForm", new ChangePasswordForm());
		}
		if (!model.containsAttribute("emailForm")) {
			model.addAttribute("emailForm", new ChangeEmailForm());
		}
		model.addAttribute("pendingEmail", emailChanges.pendingFor(userId).orElse(null));
	}
}
