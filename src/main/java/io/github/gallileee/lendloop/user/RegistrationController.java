package io.github.gallileee.lendloop.user;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class RegistrationController {

	private final RegistrationService registrationService;

	public RegistrationController(RegistrationService registrationService) {
		this.registrationService = registrationService;
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
		try {
			registrationService.register(form);
		}
		catch (EmailAlreadyRegisteredException e) {
			result.rejectValue("email", "duplicate", "An account with this email already exists.");
			return "register";
		}
		redirect.addFlashAttribute("registered", true);
		return "redirect:/login";
	}
}
