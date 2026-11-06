package io.github.gallileee.lendloop.user;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

// The unsubscribe link from the footer and List-Unsubscribe header of every email.
//
// GET only asks to confirm: link scanners in mail systems open links in emails, and that must not
// turn anything off. POST turns the email off. It is what the confirm button sends, and what a mail
// provider sends for its own "Unsubscribe" button (RFC 8058, body List-Unsubscribe=One-Click).
@Controller
@RequestMapping("/unsubscribe")
class UnsubscribeController {

	private final UnsubscribeLinks links;
	private final SettingsService settings;

	UnsubscribeController(UnsubscribeLinks links, SettingsService settings) {
		this.links = links;
		this.settings = settings;
	}

	@GetMapping
	String confirm(@RequestParam(required = false) String user, @RequestParam(required = false) String list,
			@RequestParam(required = false) String sig, Model model, HttpServletResponse response) {
		Optional<UnsubscribeLinks.Target> target = links.verify(user, list, sig);
		if (target.isEmpty()) {
			return invalid(model, response);
		}
		model.addAttribute("state", "confirm");
		model.addAttribute("list", target.get().list());
		model.addAttribute("user", user);
		model.addAttribute("sig", sig);
		return "unsubscribe";
	}

	@PostMapping
	String unsubscribe(@RequestParam(required = false) String user, @RequestParam(required = false) String list,
			@RequestParam(required = false) String sig, Model model, HttpServletResponse response) {
		Optional<UnsubscribeLinks.Target> target = links.verify(user, list, sig);
		if (target.isEmpty() || !settings.unsubscribe(target.get().userId(), target.get().list())) {
			return invalid(model, response);
		}
		model.addAttribute("state", "done");
		model.addAttribute("list", target.get().list());
		return "unsubscribe";
	}

	// The same answer for a forged, damaged or outdated link, so it doesn't tell which accounts exist.
	private static String invalid(Model model, HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		model.addAttribute("state", "invalid");
		return "unsubscribe";
	}
}
