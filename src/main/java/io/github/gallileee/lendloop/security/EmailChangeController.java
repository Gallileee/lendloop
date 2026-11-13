package io.github.gallileee.lendloop.security;

import io.github.gallileee.lendloop.security.EmailChangeService.ConfirmResult;
import io.github.gallileee.lendloop.security.EmailChangeService.RequestResult;
import io.github.gallileee.lendloop.user.SettingsPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// "Change email" on the Settings page, and the link sent to the new address.
@Controller
class EmailChangeController {

	private static final Logger log = LoggerFactory.getLogger(EmailChangeController.class);

	private final EmailChangeService changes;
	private final SettingsPage settingsPage;
	private final CurrentSession currentSession;

	EmailChangeController(EmailChangeService changes, SettingsPage settingsPage, CurrentSession currentSession) {
		this.changes = changes;
		this.settingsPage = settingsPage;
		this.currentSession = currentSession;
	}

	@PostMapping("/settings/email")
	String request(@AuthenticationPrincipal AppUserDetails me,
			@Valid @ModelAttribute("emailForm") ChangeEmailForm form, BindingResult result,
			HttpServletRequest request, Model model, RedirectAttributes redirect) {
		if (!result.hasErrors()) {
			try {
				RequestResult outcome = changes.request(me.id(), form.getNewEmail(), form.getCurrentPassword(),
						request.getRemoteAddr());
				switch (outcome) {
					case SENT -> {
						redirect.addFlashAttribute("emailChange", "SENT");
						return "redirect:/settings#change-email";
					}
					case WRONG_PASSWORD -> result.rejectValue("currentPassword", "settings.password.wrong");
					case TOO_MANY_ATTEMPTS -> result.rejectValue("currentPassword", "settings.password.blocked");
					case SAME_ADDRESS -> result.rejectValue("newEmail", "settings.emailChange.same");
					case TAKEN -> result.rejectValue("newEmail", "register.email.taken");
					case TOO_MANY_REQUESTS -> result.rejectValue("newEmail", "settings.email.tooMany");
				}
			}
			catch (MailException e) {
				log.error("Could not send the email change emails", e);
				result.rejectValue("newEmail", "settings.email.failed");
			}
		}
		// The password is never sent back to the browser.
		form.setCurrentPassword("");
		settingsPage.prepare(me.id(), model);
		return "settings";
	}

	@PostMapping("/settings/email/cancel")
	String cancel(@AuthenticationPrincipal AppUserDetails me, RedirectAttributes redirect) {
		changes.cancel(me.id());
		redirect.addFlashAttribute("emailChange", "CANCELLED");
		return "redirect:/settings#change-email";
	}

	// Opening the link only shows a button, so a mail scanner opening it changes nothing.
	@GetMapping("/change-email")
	String confirmPage(@RequestParam(required = false) String token, Model model, HttpServletResponse response) {
		return changes.newEmailFor(token).map(newEmail -> {
			model.addAttribute("token", token);
			model.addAttribute("newEmail", newEmail);
			return "change-email";
		}).orElseGet(() -> invalid(response));
	}

	// Works with or without a login: the link may be opened on another device.
	@PostMapping("/change-email")
	String confirm(@RequestParam(required = false) String token, @AuthenticationPrincipal AppUserDetails me,
			HttpServletRequest request, HttpServletResponse response, Model model, RedirectAttributes redirect) {
		return changes.confirm(token).map(confirmation -> {
			if (confirmation.result() == ConfirmResult.TAKEN) {
				response.setStatus(HttpServletResponse.SC_CONFLICT);
				model.addAttribute("taken", true);
				return "change-email-invalid";
			}
			redirect.addFlashAttribute("emailChanged", true);
			// Every session of the account was ended; this one may stay if it is the same user.
			if (me != null && me.id().equals(confirmation.user().getId())) {
				currentSession.refreshWithNewId(confirmation.user(), request, response);
				return "redirect:/settings";
			}
			return "redirect:/login";
		}).orElseGet(() -> invalid(response));
	}

	private static String invalid(HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		return "change-email-invalid";
	}
}
