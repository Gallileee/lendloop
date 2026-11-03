package io.github.gallileee.lendloop.security;

import io.github.gallileee.lendloop.user.AppUserRepository;
import io.github.gallileee.lendloop.user.RegistrationService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

	private final AppUserRepository users;

	public AppUserDetailsService(AppUserRepository users) {
		this.users = users;
	}

	@Override
	public UserDetails loadUserByUsername(String email) {
		return users.findByEmail(RegistrationService.normalizeEmail(email))
			.map(AppUserDetails::from)
			.orElseThrow(() -> new UsernameNotFoundException("No user with that email"));
	}
}
