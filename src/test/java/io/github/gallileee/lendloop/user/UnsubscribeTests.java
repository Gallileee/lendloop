package io.github.gallileee.lendloop.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.gallileee.lendloop.WebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

// The unsubscribe link over HTTP, logged out, as from an email or a mail provider.
class UnsubscribeTests extends WebTest {

	@Autowired
	AppUserRepository users;

	@Autowired
	UnsubscribeLinks links;

	AppUser sam;

	@BeforeEach
	void createUser() {
		sam = users.save(new AppUser("sam@example.com", "Sam", "{noop}irrelevant"));
	}

	private AppUser reloaded() {
		return users.findById(sam.getId()).orElseThrow();
	}

	// The path and query of a link, as MockMvc wants them.
	private String link(EmailList list) {
		return links.urlFor(sam.getId(), list).replace("https://lendloop.test", "");
	}

	@Test
	void openingTheLinkOnlyAsksAndChangesNothing() throws Exception {
		// Mail systems scan links in emails; a GET must not unsubscribe anyone.
		mvc.perform(get(link(EmailList.BORROWED)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Stop these emails?")))
			.andExpect(content().string(containsString("reminders about things you borrowed")))
			.andExpect(content().string(containsString("method=\"post\"")));

		assertThat(reloaded().isRemindBorrowed()).isTrue();
	}

	@Test
	void theConfirmButtonTurnsOffOnlyThatList() throws Exception {
		mvc.perform(post(link(EmailList.BORROWED)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("You're unsubscribed")));

		assertThat(reloaded().isRemindBorrowed()).isFalse();
		assertThat(reloaded().isNotifyLentOverdue()).isTrue();
	}

	@Test
	void aMailProvidersOneClickPostWorksWithoutCookiesOrCsrfTokenAndCanBeRepeated() throws Exception {
		// RFC 8058: form-encoded body "List-Unsubscribe=One-Click", no session, no CSRF token.
		for (int i = 0; i < 2; i++) {
			mvc.perform(post(link(EmailList.LENT))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.content("List-Unsubscribe=One-Click"))
				.andExpect(status().isOk());
		}

		assertThat(reloaded().isNotifyLentOverdue()).isFalse();
		assertThat(reloaded().isRemindBorrowed()).isTrue();
	}

	@Test
	void aForgedLinkChangesNothing() throws Exception {
		AppUser alex = users.save(new AppUser("alex@example.com", "Alex", "{noop}irrelevant"));
		// Sam's signature with Alex's id.
		String forged = link(EmailList.BORROWED).replace("user=" + sam.getId(), "user=" + alex.getId());

		mvc.perform(get(forged))
			.andExpect(status().isNotFound())
			.andExpect(content().string(containsString("This link doesn't work")));
		mvc.perform(post(forged)).andExpect(status().isNotFound());
		mvc.perform(post("/unsubscribe")).andExpect(status().isNotFound());

		assertThat(users.findById(alex.getId()).orElseThrow().isRemindBorrowed()).isTrue();
	}

	@Test
	void aLinkForADeletedAccountLooksLikeAnyOtherBadLink() throws Exception {
		String url = link(EmailList.BORROWED);
		users.deleteById(sam.getId());

		mvc.perform(post(url))
			.andExpect(status().isNotFound())
			.andExpect(content().string(containsString("This link doesn't work")));
	}

	@Test
	void otherPostsStillNeedACsrfToken() throws Exception {
		// The CSRF exception is only for /unsubscribe.
		mvc.perform(post("/register").param("email", "x@example.com")).andExpect(status().isForbidden());
	}
}
