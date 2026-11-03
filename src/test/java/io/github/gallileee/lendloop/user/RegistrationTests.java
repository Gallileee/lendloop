package io.github.gallileee.lendloop.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.gallileee.lendloop.WebTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class RegistrationTests extends WebTest {

	@Autowired
	AppUserRepository users;

	private static MockHttpServletRequestBuilder register(String email, String password, String confirm) {
		return post("/register").with(csrf())
			.param("email", email)
			.param("displayName", "Alex")
			.param("password", password)
			.param("confirmPassword", confirm);
	}

	@Test
	void pagesNeedALogin() throws Exception {
		mvc.perform(get("/items")).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/login")).andExpect(status().isOk());
		mvc.perform(get("/register")).andExpect(status().isOk());
	}

	@Test
	void signUpStoresANormalizedEmailAndAHashedPassword() throws Exception {
		mvc.perform(register("  Alex@Example.COM ", "correct horse battery", "correct horse battery"))
			.andExpect(redirectedUrl("/login"));

		AppUser user = users.findByEmail("alex@example.com").orElseThrow();
		assertThat(user.getDisplayName()).isEqualTo("Alex");
		assertThat(user.getPasswordHash()).startsWith("{bcrypt}").doesNotContain("correct horse");
	}

	@Test
	void anEmailCanOnlyBeRegisteredOnceRegardlessOfCase() throws Exception {
		mvc.perform(register("sam@example.com", "correct horse battery", "correct horse battery"));
		mvc.perform(register("SAM@example.com", "another long password", "another long password"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("An account with this email already exists.")));
		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void invalidInputShowsTheErrorsAndCreatesNoAccount() throws Exception {
		mvc.perform(register("not-an-email", "short", "different"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Enter a valid email address.")))
			.andExpect(content().string(containsString("Use at least 10 characters.")))
			.andExpect(content().string(containsString("The passwords don&#39;t match.")));
		assertThat(users.count()).isZero();
	}

	@Test
	void passwordsLongerThanBcryptCanReadAreRejected() throws Exception {
		// 40 two-byte characters: only 40 characters, but 80 bytes.
		String password = "ř".repeat(40);
		mvc.perform(register("long@example.com", password, password))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("That password is too long")));
		assertThat(users.count()).isZero();
	}

	@Test
	void formsWithoutACsrfTokenAreRefused() throws Exception {
		mvc.perform(post("/register").param("email", "x@example.com")).andExpect(status().isForbidden());
	}

	@Test
	void aRegisteredUserCanLogInWithAnyCaseOfTheirEmail() throws Exception {
		mvc.perform(register("kim@example.com", "correct horse battery", "correct horse battery"));

		mvc.perform(formLogin("/login").userParameter("email").user("Kim@Example.com").password("correct horse battery"))
			.andExpect(authenticated().withUsername("kim@example.com"))
			.andExpect(redirectedUrl("/items"));
		mvc.perform(formLogin("/login").userParameter("email").user("kim@example.com").password("wrong password!"))
			.andExpect(unauthenticated())
			.andExpect(redirectedUrl("/login?error"));
	}
}
