package io.github.gallileee.lendloop.group;

import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.loan.LoanService;
import io.github.gallileee.lendloop.security.AppUserDetails;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
@RequestMapping("/groups")
public class GroupController {

	private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

	private final GroupService groupService;
	private final LoanService loanService;

	public GroupController(GroupService groupService, LoanService loanService) {
		this.groupService = groupService;
		this.loanService = loanService;
	}

	@GetMapping
	String list(@AuthenticationPrincipal AppUserDetails me, Model model) {
		model.addAttribute("memberships", groupService.membershipsOf(me.id()));
		if (!model.containsAttribute("form")) {
			model.addAttribute("form", new GroupForm());
		}
		return "groups/list";
	}

	@PostMapping
	String create(@AuthenticationPrincipal AppUserDetails me, @Valid @ModelAttribute("form") GroupForm form,
			BindingResult result, Model model, RedirectAttributes redirect) {
		if (result.hasErrors()) {
			model.addAttribute("memberships", groupService.membershipsOf(me.id()));
			return "groups/list";
		}
		AppGroup group = groupService.create(me.id(), form);
		redirect.addFlashAttribute("message", "Group created. Send the invite link below to your friends.");
		return "redirect:/groups/" + group.getId();
	}

	@GetMapping("/{id}")
	String show(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id,
			@RequestParam(required = false) String q, @RequestParam(required = false) String category,
			@RequestParam(required = false) String available, Model model) {
		GroupService.GroupPage page = groupService.page(id, me.id(), ItemFilter.parse(q, category, available));
		model.addAttribute("page", page);
		model.addAttribute("categories", Category.values());
		// Which items are out, and which I've already asked for, so each gets the right button.
		model.addAttribute("loans", loanService.stateFor(page.items(), me.id()));
		if (page.me().isOwner()) {
			// Absolute, so it can be pasted into a chat. Behind a reverse proxy this needs
			// server.forward-headers-strategy so the public host and https are used.
			model.addAttribute("inviteUrl", ServletUriComponentsBuilder.fromCurrentContextPath()
				.path("/join/{token}")
				.buildAndExpand(page.group().getInviteToken())
				.toUriString());
			// In the server's time zone, and in English like the rest of the UI (formatting with
			// the browser's locale gave "10 lis 2026" on a Czech browser). Per-user time zones and
			// languages can come with user settings.
			model.addAttribute("inviteExpiresOn", EXPIRY_FORMAT.format(
					LocalDateTime.ofInstant(page.group().getInviteExpiresAt(), ZoneId.systemDefault())));
		}
		return "groups/detail";
	}

	@PostMapping("/{id}/invite")
	String replaceInvite(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id,
			RedirectAttributes redirect) {
		groupService.replaceInvite(id, me.id());
		redirect.addFlashAttribute("message", "New invite link created. The old one no longer works.");
		return "redirect:/groups/" + id;
	}

	@PostMapping("/{id}/leave")
	String leave(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id, RedirectAttributes redirect) {
		try {
			groupService.leave(id, me.id());
		}
		catch (GroupService.OwnerCannotLeaveException e) {
			redirect.addFlashAttribute("error", "You created this group, so you can't leave it.");
			return "redirect:/groups/" + id;
		}
		redirect.addFlashAttribute("message", "You left the group.");
		return "redirect:/groups";
	}
}
