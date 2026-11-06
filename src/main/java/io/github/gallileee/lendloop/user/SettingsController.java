package io.github.gallileee.lendloop.user;

import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.security.ChangePasswordForm;
import io.github.gallileee.lendloop.security.CurrentSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SettingsController {

	private final SettingsService settingsService;
	private final CurrentSession currentSession;

	public SettingsController(SettingsService settingsService, CurrentSession currentSession) {
		this.settingsService = settingsService;
		this.currentSession = currentSession;
	}

	@GetMapping("/settings")
	String form(@AuthenticationPrincipal AppUserDetails me, Model model) {
		model.addAttribute("form", settingsService.formFor(me.id()));
		if (!model.containsAttribute("passwordForm")) {
			model.addAttribute("passwordForm", new ChangePasswordForm());
		}
		return "settings";
	}

	@PostMapping("/settings")
	String save(@AuthenticationPrincipal AppUserDetails me, @Valid @ModelAttribute("form") SettingsForm form,
			BindingResult result, HttpServletRequest request, HttpServletResponse response, Model model,
			RedirectAttributes redirect) {
		if (result.hasErrors()) {
			model.addAttribute("passwordForm", new ChangePasswordForm());
			return "settings";
		}
		AppUser user = settingsService.save(me.id(), form);
		// The top bar shows the name from the session's principal; replace it so the new name
		// appears at once.
		currentSession.refresh(user, request, response);
		redirect.addFlashAttribute("saved", true);
		return "redirect:/settings";
	}
}
