package io.github.gallileee.lendloop.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration
public class SecurityConfig {

	// Everything needs a login except sign-up, login and the stylesheet.
	// CSRF protection stays on; Thymeleaf adds the token to every POST form.
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/login", "/register", "/css/**", "/error").permitAll()
				// Unsubscribe links are signed per user (UnsubscribeLinks) and work without a login.
				.requestMatchers("/unsubscribe").permitAll()
				.anyRequest().authenticated())
			// After login, go back to the page that asked for it (e.g. an invite link), else to /items.
			.formLogin(form -> form
				.loginPage("/login")
				.usernameParameter("email")
				.defaultSuccessUrl("/items")
				.permitAll())
			// RFC 8058: the mail provider POSTs to the unsubscribe link without cookies, so it can't
			// send a CSRF token. The signature in the link already proves it came from our email.
			.csrf(csrf -> csrf.ignoringRequestMatchers("/unsubscribe"))
			.logout(logout -> logout.logoutSuccessUrl("/login?logout"))
			// Invite tokens are in URLs; never send them to other sites in the Referer header.
			.headers(headers -> headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN)));
		return http.build();
	}

	// BCrypt, stored with a {bcrypt} prefix so the algorithm can be upgraded later without a migration.
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}
}
