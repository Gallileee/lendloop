package io.github.gallileee.lendloop.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.gallileee.lendloop.WebTest;
import io.github.gallileee.lendloop.book.BookInfo;
import io.github.gallileee.lendloop.book.BookLookup;
import io.github.gallileee.lendloop.book.BookLookup.BookLookupUnavailableException;
import io.github.gallileee.lendloop.book.Isbn;
import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// "Look it up by ISBN" on the Add item page, and the ISBN stored with an item. Open Library
// itself is replaced by a mock here; OpenLibraryClientTest covers the HTTP side.
class IsbnLookupTests extends WebTest {

	private static final Isbn FOX = new Isbn("9780140328721");

	@MockitoBean
	BookLookup bookLookup;

	@Autowired
	AppUserRepository users;

	@Autowired
	ItemRepository items;

	AppUser alex;

	@BeforeEach
	void createUser() {
		alex = users.save(new AppUser("alex@example.com", "Alex", "{noop}irrelevant"));
	}

	private RequestPostProcessor asAlex() {
		return user(AppUserDetails.from(alex));
	}

	@Test
	void aFoundBookFillsInTheForm() throws Exception {
		given(bookLookup.find(FOX)).willReturn(Optional.of(
				new BookInfo("Fantastic Mr. Fox", null, List.of("Roald Dahl"), "Puffin", "1988")));

		// Typed as the ISBN-10, with hyphens: looked up as the ISBN-13.
		mvc.perform(get("/items/new").param("isbn", "0-14-032872-6").with(asAlex()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Found it on Open Library.")))
			.andExpect(content().string(containsString("value=\"Fantastic Mr. Fox\"")))
			.andExpect(content().string(containsString("By Roald Dahl &middot; Puffin, 1988")))
			.andExpect(content().string(containsString("value=\"9780140328721\"")))
			.andExpect(content().string(containsString("<option value=\"BOOK\" selected=\"selected\"")));
	}

	@Test
	void anUnknownIsbnStillPrefillsTheIsbnAndCategory() throws Exception {
		given(bookLookup.find(FOX)).willReturn(Optional.empty());

		mvc.perform(get("/items/new").param("isbn", "9780140328721").with(asAlex()))
			.andExpect(content().string(containsString("Open Library doesn&#39;t know that ISBN.")))
			.andExpect(content().string(containsString("<option value=\"BOOK\" selected=\"selected\"")))
			.andExpect(content().string(containsString("value=\"9780140328721\"")));
	}

	@Test
	void anInvalidIsbnIsNotLookedUp() throws Exception {
		mvc.perform(get("/items/new").param("isbn", "9780140328722").with(asAlex()))
			.andExpect(content().string(containsString("That isn&#39;t a valid ISBN.")))
			// Shown again so the typo can be fixed.
			.andExpect(content().string(containsString("value=\"9780140328722\"")));
		mvc.perform(get("/items/new").param("isbn", "9".repeat(31)).with(asAlex()))
			.andExpect(content().string(containsString("That isn&#39;t a valid ISBN.")))
			.andExpect(content().string(not(containsString("9".repeat(31)))));

		verifyNoInteractions(bookLookup);
	}

	@Test
	void anUnreachableCatalogueGivesAFriendlyMessage() throws Exception {
		given(bookLookup.find(any())).willThrow(new BookLookupUnavailableException("down", null));

		mvc.perform(get("/items/new").param("isbn", "9780140328721").with(asAlex()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Open Library didn&#39;t answer.")));
	}

	@Test
	void thePlainAddPageDoesNotCallTheCatalogue() throws Exception {
		mvc.perform(get("/items/new").with(asAlex()))
			.andExpect(content().string(containsString("Look it up by ISBN")))
			.andExpect(content().string(not(containsString("Open Library didn"))));

		verifyNoInteractions(bookLookup);
	}

	@Test
	void theIsbnIsSavedAsIsbn13AndShownInTheList() throws Exception {
		mvc.perform(post("/items").with(asAlex()).with(csrf())
				.param("name", "Fantastic Mr. Fox")
				.param("category", "BOOK")
				.param("isbn", " 0-14-032872-6 "))
			.andExpect(redirectedUrl("/items"));

		assertThat(items.findByOwnerIdOrderByNameAsc(alex.getId())).singleElement()
			.extracting(Item::getIsbn).isEqualTo("9780140328721");
		mvc.perform(get("/items").with(asAlex()))
			.andExpect(content().string(containsString("ISBN 9780140328721")));
	}

	@Test
	void aWrongIsbnIsRejectedAndABlankOneClearsIt() throws Exception {
		mvc.perform(post("/items").with(asAlex()).with(csrf())
				.param("name", "Dune").param("category", "BOOK").param("isbn", "12345"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("That isn&#39;t a valid ISBN. Check the digits")));
		assertThat(items.count()).isZero();

		Item item = new Item(alex, "Dune", null, Category.BOOK, true);
		item.setIsbn(FOX);
		item = items.save(item);
		mvc.perform(post("/items/{id}", item.getId()).with(asAlex()).with(csrf())
				.param("name", "Dune").param("category", "BOOK").param("isbn", " "))
			.andExpect(redirectedUrl("/items"));
		assertThat(items.findById(item.getId())).get().extracting(Item::getIsbn).isNull();
	}

	@Test
	void theEditPageShowsTheIsbnButNoLookupBox() throws Exception {
		Item item = new Item(alex, "Fantastic Mr. Fox", null, Category.BOOK, true);
		item.setIsbn(FOX);
		item = items.save(item);

		mvc.perform(get("/items/{id}/edit", item.getId()).with(asAlex()))
			.andExpect(content().string(containsString("value=\"9780140328721\"")))
			.andExpect(content().string(not(containsString("Look it up by ISBN"))));
	}
}
