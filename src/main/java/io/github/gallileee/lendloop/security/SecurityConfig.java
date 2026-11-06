package io.github.gallileee.lendloop.security;

import io.github.gallileee.lendloop.user.AppUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration
public class SecurityConfig {

	// Everything needs a login except sign-up, login and the stylesheet.
	// CSRF protection stays on; Thymeleaf adds the token to every POST form.
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, LoginThrottle loginThrottle, AppUserRepository users)
			throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/login", "/register", "/css/**", "/error").permitAll()
				// Getting back into an account without its password.
				.requestMatchers("/forgot-password", "/forgot-password/sent", "/reset-password").permitAll()
				// Unsubscribe links are signed per user (UnsubscribeLinks) and work without a login.
				.requestMatchers("/unsubscribe").permitAll()
				.anyRequest().authenticated())
			// After login, go back to the page that asked for it (e.g. an invite link), else to /items.
			.formLogin(form -> form
				.loginPage("/login")
				.usernameParameter("email")
				.successHandler(countingSuccessHandler(loginThrottle))
				.failureHandler(countingFailureHandler(loginThrottle))
				.permitAll())
			// Too many failed logins: refused before the password is checked (after the CSRF check).
			.addFilterBefore(new LoginThrottleFilter(loginThrottle), UsernamePasswordAuthenticationFilter.class)
			// Sessions from before a password change or "log out everywhere" end here.
			.addFilterBefore(new SessionVersionFilter(users), AuthorizationFilter.class)
			// RFC 8058: the mail provider POSTs to the unsubscribe link without cookies, so it can't
			// send a CSRF token. The signature in the link already proves it came from our email.
			.csrf(csrf -> csrf.ignoringRequestMatchers("/unsubscribe"))
			.logout(logout -> logout.logoutSuccessUrl("/login?logout"))
			// Invite tokens are in URLs; never send them to other sites in the Referer header.
			.headers(headers -> headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN)));
		return http.build();
	}

	// What defaultSuccessUrl("/items") did (back to the saved page, else /items), and the email's
	// failed attempts are forgotten.
	private static AuthenticationSuccessHandler countingSuccessHandler(LoginThrottle throttle) {
		SavedRequestAwareAuthenticationSuccessHandler redirect = new SavedRequestAwareAuthenticationSuccessHandler();
		redirect.setDefaultTargetUrl("/items");
		return (request, response, authentication) -> {
			throttle.recordSuccess(request.getParameter("email"));
			redirect.onAuthenticationSuccess(request, response, authentication);
		};
	}

	// Counts the failure. The attempt that reaches the limit already says "too many attempts", so
	// the next one isn't a surprise.
	private static AuthenticationFailureHandler countingFailureHandler(LoginThrottle throttle) {
		return (request, response, exception) -> {
			String email = request.getParameter("email");
			throttle.recordFailure(email, request.getRemoteAddr());
			boolean blocked = throttle.blockedFor(email, request.getRemoteAddr()).isPresent();
			response.sendRedirect(request.getContextPath() + (blocked ? "/login?blocked" : "/login?error"));
		};
	}

	// BCrypt, stored with a {bcrypt} prefix so the algorithm can be upgraded later without a migration.
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}
}
