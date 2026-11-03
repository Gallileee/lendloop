package io.github.gallileee.lendloop.user;

import io.github.gallileee.lendloop.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SettingsController {

	private final SettingsService settingsService;
	private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
	private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

	public SettingsController(SettingsService settingsService) {
		this.settingsService = settingsService;
	}

	@GetMapping("/settings")
	String form(@AuthenticationPrincipal AppUserDetails me, Model model) {
		model.addAttribute("form", settingsService.formFor(me.id()));
		return "settings";
	}

	@PostMapping("/settings")
	String save(@AuthenticationPrincipal AppUserDetails me, @Valid @ModelAttribute("form") SettingsForm form,
			BindingResult result, HttpServletRequest request, HttpServletResponse response,
			RedirectAttributes redirect) {
		if (result.hasErrors()) {
			return "settings";
		}
		AppUser user = settingsService.save(me.id(), form);
		refreshPrincipal(user, request, response);
		redirect.addFlashAttribute("saved", true);
		return "redirect:/settings";
	}

	// The top bar shows the name from the principal stored in the session at login. Replace it,
	// or a new name would only appear after logging in again. Since Spring Security 6 a changed
	// context is not saved to the session automatically, hence saveContext.
	private void refreshPrincipal(AppUser user, HttpServletRequest request, HttpServletResponse response) {
		AppUserDetails details = AppUserDetails.from(user);
		SecurityContext context = contextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(details, null,
				details.getAuthorities()));
		contextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);
	}
}
