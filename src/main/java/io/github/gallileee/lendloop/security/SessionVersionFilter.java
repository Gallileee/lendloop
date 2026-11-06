package io.github.gallileee.lendloop.security;

import io.github.gallileee.lendloop.user.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

// Ends a session whose user has since changed their password or logged out everywhere: the
// session's principal remembers the user's session_version at login, and the database has the
// current one. Also ends sessions of accounts that no longer exist.
//
// Works without a session registry, so it also holds with several app instances. The price is one
// small query (one column by primary key) per logged-in request.
// Not a @Component: Spring Boot would also register it outside Spring Security.
class SessionVersionFilter extends OncePerRequestFilter {

	private final AppUserRepository users;
	private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();

	SessionVersionFilter(AppUserRepository users) {
		this.users = users;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AppUserDetails me) {
			Optional<Integer> current = users.findSessionVersion(me.id());
			if (current.isEmpty() || current.get() != me.sessionVersion()) {
				logout.logout(request, response, authentication);
				response.sendRedirect(request.getContextPath() + "/login?ended");
				return;
			}
		}
		chain.doFilter(request, response);
	}
}
