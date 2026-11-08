package io.github.gallileee.lendloop.group;

import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.web.Messages;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// The invite link. Opening it only shows a confirmation; joining needs a POST, so link
// previews in chat apps (which fetch the URL) can't add anyone to a group.
// Someone who isn't logged in is sent to the login page and brought back here afterwards.
@Controller
public class JoinController {

	private final GroupService groupService;
	private final Messages messages;

	public JoinController(GroupService groupService, Messages messages) {
		this.groupService = groupService;
		this.messages = messages;
	}

	@GetMapping("/join/{token}")
	String confirm(@AuthenticationPrincipal AppUserDetails me, @PathVariable String token, Model model,
			HttpServletResponse response) {
		return groupService.findByValidInvite(token).map(group -> {
			if (groupService.isMember(group.getId(), me.id())) {
				return "redirect:/groups/" + group.getId();
			}
			model.addAttribute("group", group);
			model.addAttribute("token", token);
			return "groups/join";
		}).orElseGet(() -> invalid(response));
	}

	@PostMapping("/join/{token}")
	String join(@AuthenticationPrincipal AppUserDetails me, @PathVariable String token, HttpServletResponse response,
			RedirectAttributes redirect) {
		try {
			return groupService.join(token, me.id()).map(group -> {
				redirect.addFlashAttribute("message", messages.text("group.flash.welcome", group.getName()));
				return "redirect:/groups/" + group.getId();
			}).orElseGet(() -> invalid(response));
		}
		catch (DataIntegrityViolationException e) {
			// Joined in another request at the same moment: the membership exists, so just go there.
			return groupService.findByValidInvite(token)
				.map(group -> "redirect:/groups/" + group.getId())
				.orElse("redirect:/groups");
		}
	}

	private static String invalid(HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		return "groups/invite-invalid";
	}
}
