package io.github.gallileee.lendloop.web;

import io.github.gallileee.lendloop.security.LoginThrottle;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

	private final LoginThrottle loginThrottle;

	public PageController(LoginThrottle loginThrottle) {
		this.loginThrottle = loginThrottle;
	}

	@GetMapping("/")
	String home() {
		return "redirect:/items";
	}

	@GetMapping("/login")
	String login(Model model) {
		model.addAttribute("lockoutMinutes", loginThrottle.window().toMinutes());
		return "login";
	}
}
