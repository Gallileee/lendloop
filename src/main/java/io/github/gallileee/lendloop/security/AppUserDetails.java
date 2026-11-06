package io.github.gallileee.lendloop.security;

import io.github.gallileee.lendloop.user.AppUser;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

// The logged-in user as Spring Security sees it. Keeps the database id, so
// controllers can scope queries to the current user without another lookup, and the session
// version it logged in with (SessionVersionFilter).
public record AppUserDetails(Long id, String email, String displayName, String passwordHash, int sessionVersion)
		implements UserDetails {

	public static AppUserDetails from(AppUser user) {
		return new AppUserDetails(user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash(),
				user.getSessionVersion());
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_USER"));
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return email;
	}
}
