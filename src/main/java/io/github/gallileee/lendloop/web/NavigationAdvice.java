package io.github.gallileee.lendloop.web;

import io.github.gallileee.lendloop.loan.LoanService;
import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.user.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

// Data for the top bar on every page: which section is open (Thymeleaf 3.1 no longer exposes
// the request to templates), how many borrow requests wait for the user's answer, and whether
// to ask the user to confirm their email. Also whether the user wants book covers.
@ControllerAdvice
public class NavigationAdvice {

	private final LoanService loanService;
	private final AppUserRepository users;

	public NavigationAdvice(LoanService loanService, AppUserRepository users) {
		this.loanService = loanService;
		this.users = users;
	}

	@ModelAttribute("section")
	String section(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		if (path.startsWith("/items")) {
			return "items";
		}
		if (path.startsWith("/groups") || path.startsWith("/join")) {
			return "groups";
		}
		if (path.startsWith("/loans")) {
			return "loans";
		}
		if (path.startsWith("/settings")) {
			return "settings";
		}
		return "";
	}

	// One count query per page view. Skipped for POSTs, which only redirect.
	@ModelAttribute("openRequests")
	long openRequests(@AuthenticationPrincipal AppUserDetails me, HttpServletRequest request) {
		if (me == null || !"GET".equals(request.getMethod())) {
			return 0;
		}
		return loanService.openRequestsFor(me.id());
	}

	// Read from the database, not the session: the link may have been opened in another browser.
	@ModelAttribute("emailUnconfirmed")
	boolean emailUnconfirmed(@AuthenticationPrincipal AppUserDetails me, HttpServletRequest request) {
		return me != null && "GET".equals(request.getMethod()) && users.existsByIdAndEmailVerifiedAtIsNull(me.id());
	}

	// Book covers are opt-in (Settings), so nobody's IP address goes to Open Library unasked.
	// Only the two pages that list items need it.
	@ModelAttribute("showCovers")
	boolean showCovers(@AuthenticationPrincipal AppUserDetails me, HttpServletRequest request) {
		String section = section(request);
		return me != null && "GET".equals(request.getMethod())
				&& (section.equals("items") || section.equals("groups"))
				&& users.existsByIdAndShowBookCoversTrue(me.id());
	}
}
