package io.github.gallileee.lendloop.web;

import io.github.gallileee.lendloop.loan.LoanService;
import io.github.gallileee.lendloop.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

// Data for the top bar on every page: which section is open (Thymeleaf 3.1 no longer exposes
// the request to templates), and how many borrow requests wait for the user's answer.
@ControllerAdvice
public class NavigationAdvice {

	private final LoanService loanService;

	public NavigationAdvice(LoanService loanService) {
		this.loanService = loanService;
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
}
