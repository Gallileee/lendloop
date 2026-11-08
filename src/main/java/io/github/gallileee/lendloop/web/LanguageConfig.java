package io.github.gallileee.lendloop.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// English and Czech pages. Any page can be opened with ?lang=cs or ?lang=en (the switcher in
// the footer links there); the choice is kept in a cookie. A GET is fine here: switching the
// language changes nothing but how this browser's pages look, so it needs no CSRF token.
@Configuration
public class LanguageConfig implements WebMvcConfigurer {

	static final String PARAM = "lang";

	// The bean name "localeResolver" replaces Spring Boot's default (Accept-Language only).
	@Bean
	public LocaleResolver localeResolver() {
		return new LanguageResolver();
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		LocaleResolver resolver = localeResolver();
		registry.addInterceptor(new HandlerInterceptor() {
			@Override
			public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
				Locale chosen = LanguageResolver.supported(request.getParameter(PARAM));
				if (chosen != null) {
					resolver.setLocale(request, response, chosen);
				}
				return true;
			}
		});
	}
}
