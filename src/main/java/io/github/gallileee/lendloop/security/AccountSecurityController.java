package io.github.gallileee.lendloop.security;

import io.github.gallileee.lendloop.user.SettingsPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// The password and sessions parts of the Settings page (SettingsController shows the page).
@Controller
class AccountSecurityController {

	private final AccountSecurityService accounts;
	private final SettingsPage settingsPage;
	private final CurrentSession currentSession;

	AccountSecurityController(AccountSecurityService accounts, SettingsPage settingsPage,
			CurrentSession currentSession) {
		this.accounts = accounts;
		this.settingsPage = settingsPage;
		this.currentSession = currentSession;
	}

	@PostMapping("/settings/password")
	String changePassword(@AuthenticationPrincipal AppUserDetails me,
			@Valid @ModelAttribute("passwordForm") ChangePasswordForm form, BindingResult result,
			HttpServletRequest request, HttpServletResponse response, Model model, RedirectAttributes redirect) {
		if (!result.hasErrors()) {
			AccountSecurityService.PasswordChange change = accounts.changePassword(me.id(), form.getCurrentPassword(),
					form.getNewPassword(), request.getRemoteAddr());
			switch (change.result()) {
				case CHANGED -> {
					currentSession.refreshWithNewId(change.user(), request, response);
					redirect.addFlashAttribute("passwordChanged", true);
					return "redirect:/settings";
				}
				case WRONG_PASSWORD -> result.rejectValue("currentPassword", "settings.password.wrong");
				case TOO_MANY_ATTEMPTS -> result.rejectValue("currentPassword", "settings.password.blocked");
			}
		}
		// Passwords are never sent back to the browser.
		form.setCurrentPassword("");
		form.setNewPassword("");
		form.setConfirmPassword("");
		settingsPage.prepare(me.id(), model);
		return "settings";
	}

	@PostMapping("/settings/sessions")
	String endOtherSessions(@AuthenticationPrincipal AppUserDetails me, HttpServletRequest request,
			HttpServletResponse response, RedirectAttributes redirect) {
		currentSession.refresh(accounts.endOtherSessions(me.id()), request, response);
		redirect.addFlashAttribute("sessionsEnded", true);
		return "redirect:/settings";
	}
}
