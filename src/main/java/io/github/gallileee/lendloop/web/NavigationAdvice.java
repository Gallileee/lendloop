package io.github.gallileee.lendloop.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

// Tells the top bar which section is open, so it can mark that link (Thymeleaf 3.1 no longer
// exposes the request to templates).
@ControllerAdvice
public class NavigationAdvice {

	@ModelAttribute("section")
	String section(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		if (path.startsWith("/items")) {
			return "items";
		}
		if (path.startsWith("/groups") || path.startsWith("/join")) {
			return "groups";
		}
		return "";
	}
}
