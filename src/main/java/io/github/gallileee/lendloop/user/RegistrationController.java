package io.github.gallileee.lendloop.user;

import io.github.gallileee.lendloop.security.EmailVerificationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class RegistrationController {

	private static final Logger log = LoggerFactory.getLogger(RegistrationController.class);

	private final RegistrationService registrationService;
	private final EmailVerificationService verifications;

	public RegistrationController(RegistrationService registrationService, EmailVerificationService verifications) {
		this.registrationService = registrationService;
		this.verifications = verifications;
	}

	@GetMapping("/register")
	String form(@ModelAttribute("form") RegistrationForm form) {
		return "register";
	}

	@PostMapping("/register")
	String register(@Valid @ModelAttribute("form") RegistrationForm form, BindingResult result,
			RedirectAttributes redirect) {
		if (result.hasErrors()) {
			return "register";
		}
		AppUser user;
		try {
			user = registrationService.register(form);
		}
		catch (EmailAlreadyRegisteredException e) {
			result.rejectValue("email", "register.email.taken");
			return "register";
		}
		try {
			verifications.sendLink(user.getId());
		}
		catch (MailException e) {
			// The account is created either way; Settings offers to send the link again.
			log.error("Could not send the confirmation email for user {}", user.getId(), e);
		}
		redirect.addFlashAttribute("registered", true);
		return "redirect:/login";
	}
}
