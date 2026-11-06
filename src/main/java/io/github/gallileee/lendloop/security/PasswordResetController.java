package io.github.gallileee.lendloop.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class PasswordResetController {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetController.class);

	private final PasswordResetService resets;

	PasswordResetController(PasswordResetService resets) {
		this.resets = resets;
	}

	@GetMapping("/forgot-password")
	String forgotForm(Model model) {
		model.addAttribute("validMinutes", PasswordResetService.LINK_VALIDITY.toMinutes());
		return "forgot-password";
	}

	// Always the same answer afterwards: "if there's an account, we've sent a link".
	@PostMapping("/forgot-password")
	String requestLink(@RequestParam(required = false) String email, HttpServletRequest request, Model model,
			RedirectAttributes redirect) {
		String trimmed = email == null ? "" : email.strip();
		if (trimmed.isEmpty() || trimmed.length() > 254 || !trimmed.contains("@")) {
			model.addAttribute("validMinutes", PasswordResetService.LINK_VALIDITY.toMinutes());
			model.addAttribute("email", trimmed.length() > 254 ? "" : trimmed);
			model.addAttribute("emailError", "Enter the email address you signed up with.");
			return "forgot-password";
		}
		try {
			resets.requestReset(trimmed, request.getRemoteAddr());
		}
		catch (MailException e) {
			// Shown like a success: an error here would tell that the account exists.
			log.error("Could not send a password reset email", e);
		}
		redirect.addFlashAttribute("sentTo", trimmed);
		return "redirect:/forgot-password/sent";
	}

	@GetMapping("/forgot-password/sent")
	String sent(Model model) {
		model.addAttribute("validMinutes", PasswordResetService.LINK_VALIDITY.toMinutes());
		return "forgot-password-sent";
	}

	@GetMapping("/reset-password")
	String resetForm(@RequestParam(required = false) String token, Model model, HttpServletResponse response) {
		if (!resets.isUsable(token)) {
			return invalid(response);
		}
		ResetPasswordForm form = new ResetPasswordForm();
		form.setToken(token);
		model.addAttribute("form", form);
		return "reset-password";
	}

	@PostMapping("/reset-password")
	String reset(@Valid @ModelAttribute("form") ResetPasswordForm form, BindingResult result,
			HttpServletResponse response, RedirectAttributes redirect) {
		if (result.hasErrors()) {
			// Check the link first, so an expired one isn't answered with password advice.
			if (!resets.isUsable(form.getToken())) {
				return invalid(response);
			}
			form.setPassword("");
			form.setConfirmPassword("");
			return "reset-password";
		}
		return resets.resetPassword(form.getToken(), form.getPassword()).map(email -> {
			redirect.addFlashAttribute("passwordReset", true);
			return "redirect:/login";
		}).orElseGet(() -> invalid(response));
	}

	private static String invalid(HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		return "reset-password-invalid";
	}
}
