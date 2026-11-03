package io.github.gallileee.lendloop.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

	@GetMapping("/")
	String home() {
		return "redirect:/items";
	}

	@GetMapping("/login")
	String login() {
		return "login";
	}
}
