package io.github.gallileee.lendloop.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.gallileee.lendloop.WebTest;
import io.github.gallileee.lendloop.group.AppGroup;
import io.github.gallileee.lendloop.group.GroupForm;
import io.github.gallileee.lendloop.group.GroupService;
import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.ItemRepository;
import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Book covers are opt-in: they load from Open Library, which then sees the viewer's IP address.
class BookCoverPagesTests extends WebTest {

	// The test base URL (src/test/resources/application.properties) plus Dune's ISBN-13.
	static final String DUNE_COVER = "https://covers.openlibrary.invalid/b/isbn/9780441013593-M.jpg";

	@Autowired
	AppUserRepository users;

	@Autowired
	ItemRepository items;

	@Autowired
	GroupService groupService;

	AppUser alex;
	AppUser sam;
	AppGroup group;

	@BeforeEach
	void groupWithABook() {
		alex = users.save(new AppUser("alex@example.com", "Alex", "{noop}irrelevant"));
		sam = users.save(new AppUser("sam@example.com", "Sam", "{noop}irrelevant"));
		GroupForm form = new GroupForm();
		form.setName("Flat 4B");
		group = groupService.create(alex.getId(), form);
		groupService.join(group.getInviteToken(), sam.getId());

		Item dune = new Item(sam, "Dune", null, Category.BOOK, true);
		dune.setIsbn(Isbn.parse("9780441013593").orElseThrow());
		items.save(dune);
		items.save(new Item(sam, "Cordless drill", null, Category.TOOL, true));
	}

	private RequestPostProcessor as(AppUser who) {
		return user(AppUserDetails.from(who));
	}

	private void turnCoversOn(AppUser who) {
		AppUser stored = users.findById(who.getId()).orElseThrow();
		stored.changeBookCovers(true);
		users.save(stored);
	}

	@Test
	void coversAreOffForANewAccount() throws Exception {
		assertThat(users.findById(alex.getId()).orElseThrow().isShowBookCovers()).isFalse();

		mvc.perform(get("/groups/{id}", group.getId()).with(as(alex)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Dune")))
			.andExpect(content().string(not(containsString("covers.openlibrary"))))
			.andExpect(content().string(not(containsString("item-cover"))));
	}

	@Test
	void theGroupPageShowsTheCoverOnlyForBooksWithAnIsbn() throws Exception {
		turnCoversOn(alex);

		String html = mvc.perform(get("/groups/{id}", group.getId()).with(as(alex)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(html).contains("src=\"" + DUNE_COVER + "\"")
			.contains("referrerpolicy=\"no-referrer\"")
			.contains("loading=\"lazy\"");
		// One cover: the drill has no ISBN.
		assertThat(html.split("class=\"item-cover\"", -1)).hasSize(2);
	}

	@Test
	void theSettingIsPerViewer() throws Exception {
		turnCoversOn(alex);

		// Sam owns the book but hasn't turned covers on.
		mvc.perform(get("/groups/{id}", group.getId()).with(as(sam)))
			.andExpect(content().string(not(containsString(DUNE_COVER))));
		mvc.perform(get("/items").with(as(sam)))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(DUNE_COVER))));
	}

	@Test
	void myThingsShowsTheCoverToo() throws Exception {
		turnCoversOn(sam);

		mvc.perform(get("/items").with(as(sam)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("src=\"" + DUNE_COVER + "\"")));
	}

	@Test
	void theSettingsPageTurnsCoversOnAndOff() throws Exception {
		mvc.perform(get("/settings").with(as(alex)))
			.andExpect(content().string(containsString("Book covers")))
			.andExpect(content().string(containsString("name=\"showBookCovers\" value=\"true\">")));

		mvc.perform(post("/settings").with(as(alex)).with(csrf())
				.param("displayName", "Alex")
				.param("showBookCovers", "true")
				.param("_showBookCovers", "on"))
			.andExpect(redirectedUrl("/settings"));
		assertThat(users.findById(alex.getId()).orElseThrow().isShowBookCovers()).isTrue();
		mvc.perform(get("/settings").with(as(alex)))
			.andExpect(content().string(containsString("name=\"showBookCovers\" value=\"true\" checked")));

		// Unticked: the browser sends only Thymeleaf's hidden marker field.
		mvc.perform(post("/settings").with(as(alex)).with(csrf())
				.param("displayName", "Alex")
				.param("_showBookCovers", "on"))
			.andExpect(redirectedUrl("/settings"));
		assertThat(users.findById(alex.getId()).orElseThrow().isShowBookCovers()).isFalse();
	}

	@Test
	void theCzechSettingsPageExplainsWhyCoversAreOff() throws Exception {
		mvc.perform(get("/settings?lang=cs").with(as(alex)))
			.andExpect(content().string(containsString("Obálky knih")))
			.andExpect(content().string(containsString("uvidí vaši IP adresu")));
	}
}
