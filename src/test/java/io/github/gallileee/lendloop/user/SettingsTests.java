package io.github.gallileee.lendloop.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.gallileee.lendloop.WebTest;
import io.github.gallileee.lendloop.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class SettingsTests extends WebTest {

	@Autowired
	AppUserRepository users;

	AppUser alex;

	@BeforeEach
	void createUser() {
		alex = users.save(new AppUser("alex@example.com", "Alex", "{noop}irrelevant"));
	}

	private RequestPostProcessor asAlex() {
		return user(AppUserDetails.from(alex));
	}

	private AppUser reloaded() {
		return users.findById(alex.getId()).orElseThrow();
	}

	@Test
	void needsALogin() throws Exception {
		mvc.perform(get("/settings")).andExpect(redirectedUrl("/login"));
	}

	@Test
	void bothEmailsAreOnForANewAccount() throws Exception {
		mvc.perform(get("/settings").with(asAlex()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("value=\"Alex\"")))
			.andExpect(content().string(containsString("name=\"remindBorrowed\" value=\"true\" checked")))
			.andExpect(content().string(containsString("name=\"notifyLentOverdue\" value=\"true\" checked")));
	}

	@Test
	void savingStoresTheNameAndTheUntickedBoxes() throws Exception {
		// What a browser sends with "remind me" unticked: only Thymeleaf's hidden marker field.
		mvc.perform(post("/settings").with(asAlex()).with(csrf())
				.param("displayName", "  Alexandra ")
				.param("_remindBorrowed", "on")
				.param("notifyLentOverdue", "true")
				.param("_notifyLentOverdue", "on"))
			.andExpect(redirectedUrl("/settings"))
			.andExpect(flash().attribute("saved", true));

		AppUser saved = reloaded();
		assertThat(saved.getDisplayName()).isEqualTo("Alexandra");
		assertThat(saved.isRemindBorrowed()).isFalse();
		assertThat(saved.isNotifyLentOverdue()).isTrue();
	}

	@Test
	void theNewNameIsStoredInTheSessionSoTheTopBarShowsItAtOnce() throws Exception {
		MockHttpSession session = new MockHttpSession();
		MvcResult result = mvc.perform(post("/settings").session(session).with(asAlex()).with(csrf())
				.param("displayName", "Alexandra")
				.param("_remindBorrowed", "on")
				.param("_notifyLentOverdue", "on"))
			.andExpect(redirectedUrl("/settings"))
			.andReturn();

		SecurityContext context = (SecurityContext) result.getRequest().getSession()
			.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
		AppUserDetails principal = (AppUserDetails) context.getAuthentication().getPrincipal();
		assertThat(principal.displayName()).isEqualTo("Alexandra");
		assertThat(principal.id()).isEqualTo(alex.getId());
		assertThat(context.getAuthentication().isAuthenticated()).isTrue();
	}

	@Test
	void aBlankNameIsRefusedAndNothingChanges() throws Exception {
		mvc.perform(post("/settings").with(asAlex()).with(csrf())
				.param("displayName", "   ")
				.param("_remindBorrowed", "on")
				.param("_notifyLentOverdue", "on"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Enter the name your friends know you by.")));

		AppUser unchanged = reloaded();
		assertThat(unchanged.getDisplayName()).isEqualTo("Alex");
		assertThat(unchanged.isRemindBorrowed()).isTrue();
	}

	@Test
	void theTopBarLinksToSettings() throws Exception {
		mvc.perform(get("/settings").with(asAlex()))
			.andExpect(content().string(containsString("aria-current=\"page\">Alex</a>")));
	}
}
