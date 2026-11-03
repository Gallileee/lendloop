package io.github.gallileee.lendloop.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ItemControllerTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	AppUserRepository users;

	@Autowired
	ItemRepository items;

	AppUser alex;
	AppUser sam;

	@BeforeEach
	void createUsers() {
		alex = users.save(new AppUser("alex@example.com", "Alex", "{noop}irrelevant"));
		sam = users.save(new AppUser("sam@example.com", "Sam", "{noop}irrelevant"));
	}

	private static RequestPostProcessor as(AppUser user) {
		return user(AppUserDetails.from(user));
	}

	private Item itemOf(AppUser owner, String name) {
		return items.save(new Item(owner, name, null, Category.GAME, true));
	}

	@Test
	void addingAnItemShowsItInTheList() throws Exception {
		mvc.perform(post("/items").with(as(alex)).with(csrf())
				.param("name", "  Catan  ")
				.param("category", "GAME")
				.param("description", "Base game, all pieces")
				.param("available", "true"))
			.andExpect(redirectedUrl("/items"))
			.andExpect(flash().attribute("message", "Added “Catan”."));

		assertThat(items.findByOwnerIdOrderByNameAsc(alex.getId())).singleElement().satisfies(item -> {
			assertThat(item.getName()).isEqualTo("Catan");
			assertThat(item.getDescription()).isEqualTo("Base game, all pieces");
			assertThat(item.isAvailable()).isTrue();
		});
		mvc.perform(get("/items").with(as(alex)))
			.andExpect(content().string(containsString("Catan")))
			.andExpect(content().string(containsString("Can be lent")));
	}

	@Test
	void anItemNeedsANameAndAValidCategory() throws Exception {
		mvc.perform(post("/items").with(as(alex)).with(csrf()).param("name", " ").param("category", "GAME"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Give the item a name.")));
		mvc.perform(post("/items").with(as(alex)).with(csrf()).param("name", "Tent").param("category", "SPACESHIP"))
			.andExpect(status().isOk());
		assertThat(items.count()).isZero();
	}

	@Test
	void theListShowsOnlyMyOwnItems() throws Exception {
		itemOf(alex, "Ticket to Ride");
		itemOf(sam, "Sam's ladder");

		mvc.perform(get("/items").with(as(alex)))
			.andExpect(content().string(containsString("Ticket to Ride")))
			.andExpect(content().string(not(containsString("ladder"))));
	}

	@Test
	void editingSavesTheChangesAndCanStopLending() throws Exception {
		Item item = itemOf(alex, "Catan");

		mvc.perform(get("/items/{id}/edit", item.getId()).with(as(alex)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("value=\"Catan\"")));
		// A browser sends nothing for an unchecked checkbox, only th:field's "_available" marker,
		// which tells Spring to set it to false.
		mvc.perform(post("/items/{id}", item.getId()).with(as(alex)).with(csrf())
				.param("name", "Catan + Seafarers")
				.param("category", "GAME")
				.param("_available", "on"))
			.andExpect(redirectedUrl("/items"));

		Item saved = items.findById(item.getId()).orElseThrow();
		assertThat(saved.getName()).isEqualTo("Catan + Seafarers");
		assertThat(saved.isAvailable()).isFalse();
	}

	@Test
	void someoneElsesItemCannotBeSeenChangedOrDeleted() throws Exception {
		Item item = itemOf(alex, "Catan");

		mvc.perform(get("/items/{id}/edit", item.getId()).with(as(sam))).andExpect(status().isNotFound());
		mvc.perform(post("/items/{id}", item.getId()).with(as(sam)).with(csrf())
				.param("name", "Mine now").param("category", "GAME"))
			.andExpect(status().isNotFound());
		mvc.perform(post("/items/{id}/delete", item.getId()).with(as(sam)).with(csrf()))
			.andExpect(status().isNotFound());

		assertThat(items.findById(item.getId())).get().extracting(Item::getName).isEqualTo("Catan");
	}

	@Test
	void deletingRemovesTheItem() throws Exception {
		Item item = itemOf(alex, "Catan");

		mvc.perform(post("/items/{id}/delete", item.getId()).with(as(alex)).with(csrf()))
			.andExpect(redirectedUrl("/items"));
		assertThat(items.findById(item.getId())).isEmpty();
	}

	@Test
	void anEmptyListInvitesToAddTheFirstItem() throws Exception {
		mvc.perform(get("/items").with(as(alex)))
			.andExpect(content().string(containsString("Nothing here yet")));
	}
}
