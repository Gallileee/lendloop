package io.github.gallileee.lendloop.security;

import io.github.gallileee.lendloop.security.EmailVerificationService.ResendResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// The link from the confirmation email, and "send it again" on the Settings page.
//
// Opening the link only shows a button; the POST confirms. Mail providers' link scanners open
// links in emails by themselves, and a GET that confirmed would let a scanner confirm an address
// the user never read, which is exactly what this check is meant to catch.
@Controller
class EmailVerificationController {

	private static final Logger log = LoggerFactory.getLogger(EmailVerificationController.class);

	private final EmailVerificationService verifications;

	EmailVerificationController(EmailVerificationService verifications) {
		this.verifications = verifications;
	}

	@GetMapping("/verify-email")
	String confirmPage(@RequestParam(required = false) String token, Model model, HttpServletResponse response) {
		if (!verifications.isUsable(token)) {
			return invalid(response);
		}
		model.addAttribute("token", token);
		return "verify-email";
	}

	// Works with or without a login: the link may be opened on another device.
	@PostMapping("/verify-email")
	String confirm(@RequestParam(required = false) String token, @AuthenticationPrincipal AppUserDetails me,
			HttpServletResponse response, RedirectAttributes redirect) {
		return verifications.verify(token).map(userId -> {
			redirect.addFlashAttribute("emailVerified", true);
			return me != null ? "redirect:/settings" : "redirect:/login";
		}).orElseGet(() -> invalid(response));
	}

	@PostMapping("/settings/email/resend")
	String resend(@AuthenticationPrincipal AppUserDetails me, HttpServletRequest request,
			RedirectAttributes redirect) {
		try {
			ResendResult result = verifications.resend(me.id(), request.getRemoteAddr());
			redirect.addFlashAttribute("verification", result.name());
		}
		catch (MailException e) {
			log.error("Could not send a confirmation email", e);
			redirect.addFlashAttribute("verification", "MAIL_FAILED");
		}
		return "redirect:/settings#email";
	}

	private static String invalid(HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		return "verify-email-invalid";
	}
}
