package io.github.gallileee.lendloop.security;

import io.github.gallileee.lendloop.user.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

// Replaces the logged-in user stored in this session with the current state of the account.
@Component
public class CurrentSession {

	private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
	private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

	// After a name change, so the top bar shows the new name at once; after a password change or
	// "log out everywhere", so this session carries the new session version and stays logged in.
	// Since Spring Security 6 a changed context is not saved to the session automatically, hence
	// saveContext.
	public void refresh(AppUser user, HttpServletRequest request, HttpServletResponse response) {
		AppUserDetails details = AppUserDetails.from(user);
		SecurityContext context = contextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(details, null,
				details.getAuthorities()));
		contextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);
	}

	// Like refresh, under a new session id: after the password changes, an id someone may have
	// seen before is no longer worth anything.
	public void refreshWithNewId(AppUser user, HttpServletRequest request, HttpServletResponse response) {
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		refresh(user, request, response);
	}
}
