package io.github.gallileee.lendloop.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

// Stops a login attempt before the password is checked when LoginThrottle says to wait.
// Not a @Component: Spring Boot would also register it for every request outside Spring Security.
class LoginThrottleFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(LoginThrottleFilter.class);

	private final LoginThrottle throttle;

	LoginThrottleFilter(LoginThrottle throttle) {
		this.throttle = throttle;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// The URI without the context path, not getServletPath(): that one depends on how the
		// servlet is mapped (it's empty in MockMvc, for one).
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !("POST".equals(request.getMethod()) && "/login".equals(path));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Optional<Duration> wait = throttle.blockedFor(request.getParameter("email"), request.getRemoteAddr());
		if (wait.isPresent()) {
			log.warn("Refused a login from {}: too many failed attempts (allowed again in {} s)",
					request.getRemoteAddr(), wait.get().toSeconds());
			response.sendRedirect(request.getContextPath() + "/login?blocked");
			return;
		}
		chain.doFilter(request, response);
	}
}
