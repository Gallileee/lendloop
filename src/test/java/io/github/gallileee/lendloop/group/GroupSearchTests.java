package io.github.gallileee.lendloop.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.gallileee.lendloop.WebTest;
import io.github.gallileee.lendloop.book.Isbn;
import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.ItemForm;
import io.github.gallileee.lendloop.item.ItemRepository;
import io.github.gallileee.lendloop.item.ItemService;
import io.github.gallileee.lendloop.loan.LoanService;
import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import io.github.gallileee.lendloop.web.Dates;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Search and filters on the group page (milestone 3f). Runs without a test transaction (WebTest),
// so the owner fetch in the filtered query is checked as well.
class GroupSearchTests extends WebTest {

	@Autowired
	AppUserRepository users;

	@Autowired
	ItemRepository items;

	@Autowired
	GroupService groupService;

	@Autowired
	LoanService loanService;

	@Autowired
	ItemService itemService;

	@Autowired
	Dates dates;

	AppUser alex;
	AppUser sam;
	AppGroup group;

	@BeforeEach
	void groupWithThings() {
		alex = users.save(new AppUser("alex@example.com", "Alex", "{noop}irrelevant"));
		sam = users.save(new AppUser("sam@example.com", "Sam", "{noop}irrelevant"));
		AppUser kim = users.save(new AppUser("kim@example.com", "Kim", "{noop}irrelevant"));
		GroupForm form = new GroupForm();
		form.setName("Flat 4B");
		group = groupService.create(alex.getId(), form);
		groupService.join(group.getInviteToken(), sam.getId());

		Item dune = new Item(sam, "Dune", "By Frank Herbert · Ace", Category.BOOK, true);
		dune.setIsbn(Isbn.parse("9780441013593").orElseThrow());
		items.save(dune);
		items.save(new Item(sam, "Children of Dune", "By Frank Herbert", Category.BOOK, true));
		items.save(new Item(alex, "catan", "Base game, 100% complete", Category.GAME, true));
		items.save(new Item(alex, "Cordless drill", null, Category.TOOL, true));
		items.save(new Item(sam, "Dune (private copy)", null, Category.BOOK, false));
		items.save(new Item(kim, "Dune, Kim's", null, Category.BOOK, true));
	}

	private List<String> names(String q, String category, String available) {
		return groupService.page(group.getId(), alex.getId(), ItemFilter.parse(q, category, available))
			.items()
			.stream()
			.map(Item::getName)
			.toList();
	}

	@Test
	void withoutAFilterTheWholeGroupShowsInNameOrderIgnoringCase() {
		GroupService.GroupPage page = groupService.page(group.getId(), alex.getId());

		assertThat(page.items()).extracting(Item::getName)
			.containsExactly("catan", "Children of Dune", "Cordless drill", "Dune");
		assertThat(page.lendableCount()).isEqualTo(4);
	}

	@Test
	void textMatchesNameOrDescriptionIgnoringCase() {
		assertThat(names("DUNE", null, null)).containsExactlyInAnyOrder("Children of Dune", "Dune");
		assertThat(names("herbert", null, null)).containsExactly("Children of Dune", "Dune");
		assertThat(names("nothing like this", null, null)).isEmpty();
	}

	@Test
	void wildcardsInTheSearchAreTakenLiterally() {
		assertThat(names("100%", null, null)).containsExactly("catan");
		// Without escaping, "%" alone would match everything and "_" any single character.
		assertThat(names("%", null, null)).containsExactly("catan");
		assertThat(names("c_tan", null, null)).isEmpty();
	}

	@Test
	void accentsAreIgnoredWhicheverSideHasThem() {
		items.save(new Item(alex, "Příklepová vrtačka", "Bosch, s kufříkem", Category.TOOL, true));
		items.save(new Item(sam, "Cafe table", null, Category.OTHER, true));

		assertThat(names("vrtacka", null, null)).containsExactly("Příklepová vrtačka");
		assertThat(names("VRTAČKA", null, null)).containsExactly("Příklepová vrtačka");
		assertThat(names("kufrik", null, null)).containsExactly("Příklepová vrtačka");
		assertThat(names("café", null, null)).containsExactly("Cafe table");
	}

	@Test
	void otherCzechWordFormsFindTheThing() {
		items.save(new Item(alex, "Příklepová vrtačka", "Bosch, s kufříkem", Category.TOOL, true));
		items.save(new Item(sam, "Kniha receptů", "Petrova", Category.BOOK, true));
		items.save(new Item(sam, "Žebřík", null, Category.TOOL, true));

		assertThat(names("vrtačky", null, null)).containsExactly("Příklepová vrtačka");
		assertThat(names("vrtacce", null, null)).containsExactly("Příklepová vrtačka");
		assertThat(names("příklepovou vrtačkou", null, null)).containsExactly("Příklepová vrtačka");
		// The last word may still be being typed.
		assertThat(names("příklepové vrt", null, null)).containsExactly("Příklepová vrtačka");
		assertThat(names("kufřík", null, null)).containsExactly("Příklepová vrtačka");
		assertThat(names("knize", null, null)).containsExactly("Kniha receptů");
		assertThat(names("recepty", null, null)).containsExactly("Kniha receptů");
		assertThat(names("Petr", null, null)).containsExactly("Kniha receptů");
		assertThat(names("žebříky", null, null)).containsExactly("Žebřík");
		assertThat(names("zebricich", null, null)).containsExactly("Žebřík");
	}

	@Test
	void wordFormsStillHaveToBeTheSameWordsInTheSameOrder() {
		items.save(new Item(alex, "Příklepová vrtačka", "Bosch, s kufříkem", Category.TOOL, true));

		assertThat(names("vrtačky příklepové", null, null)).isEmpty();
		// A stem has to start a word: "rtačky" is not "vrtačka".
		assertThat(names("rtačky", null, null)).isEmpty();
		// Across the name and the description, as with the plain search.
		assertThat(names("vrtačky bosch", null, null)).isEmpty();
		assertThat(names("vrtak", null, null)).isEmpty();
	}

	@Test
	void editingAnItemUpdatesItsStems() {
		Item drill = items.findAll().stream().filter(i -> i.getName().equals("Cordless drill")).findFirst().orElseThrow();
		ItemForm form = new ItemForm();
		form.setName("Aku vrtačka");
		form.setCategory(Category.TOOL);
		form.setAvailable(true);
		itemService.update(drill.getId(), alex.getId(), form);

		assertThat(names("vrtačkou", null, null)).containsExactly("Aku vrtačka");
		assertThat(names("drills", null, null)).isEmpty();
	}

	@Test
	void aSearchDoesNotRunFromTheNameIntoTheDescription() {
		// Dune's stored text is its name and description with a newline between them, so a search
		// can't match the end of one and the start of the other.
		assertThat(names("dune by", null, null)).isEmpty();
	}

	@Test
	void editingAnItemUpdatesWhatItIsFoundBy() {
		Item drill = items.findAll().stream().filter(i -> i.getName().equals("Cordless drill")).findFirst().orElseThrow();
		ItemForm form = new ItemForm();
		form.setName("Aku vrtačka");
		form.setDescription("Makita");
		form.setCategory(Category.TOOL);
		form.setAvailable(true);
		itemService.update(drill.getId(), alex.getId(), form);

		assertThat(names("vrtacka", null, null)).containsExactly("Aku vrtačka");
		assertThat(names("makita", null, null)).containsExactly("Aku vrtačka");
		assertThat(names("cordless", null, null)).isEmpty();
	}

	@Test
	void aCzechSearchFromTheAddressBarWorks() throws Exception {
		items.save(new Item(alex, "Vrtačka", null, Category.TOOL, true));

		// A URI, not a template: a template would encode the % signs a second time.
		mvc.perform(get(URI.create("/groups/" + group.getId() + "?q=vrta%C4%8Dka")).with(as(alex)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(">Vrtačka</h3>")))
			.andExpect(content().string(containsString("value=\"vrtačka\"")));
	}

	@Test
	void closestMatchesComeFirst() {
		items.save(new Item(alex, "Sada vrtáků", "Hodí se do vrtačky", Category.TOOL, true));
		items.save(new Item(alex, "Aku vrtačka", null, Category.TOOL, true));
		items.save(new Item(sam, "Vrtačka", "Stará, ale funguje", Category.TOOL, true));
		items.save(new Item(sam, "Příklepová vrtačka", null, Category.TOOL, true));

		// Exact name, then the whole word in the name (in name order), then the description.
		assertThat(names("vrtačka", null, null))
			.containsExactly("Vrtačka", "Aku vrtačka", "Příklepová vrtačka", "Sada vrtáků");
		// Another word form in the name still beats the word in the description.
		assertThat(names("vrtačky", null, null))
			.containsExactly("Aku vrtačka", "Příklepová vrtačka", "Vrtačka", "Sada vrtáků");
		// The exact title before the title with the word further in (Kim isn't in the group).
		assertThat(names("dune", null, null)).containsExactly("Dune", "Children of Dune");
	}

	@Test
	void theFiltersAloneKeepTheNameOrder() {
		assertThat(names(null, "BOOK", "true")).containsExactly("Children of Dune", "Dune");
	}

	@Test
	void thePageSaysTheClosestMatchesComeFirstOnlyForTypedText() throws Exception {
		mvc.perform(get("/groups/{id}", group.getId()).with(as(alex)).param("q", "dune"))
			.andExpect(content().string(containsString("Closest matches first.")));
		mvc.perform(get("/groups/{id}", group.getId()).with(as(alex)).param("category", "BOOK"))
			.andExpect(content().string(not(containsString("Closest matches first."))));
		// One match has no order to explain.
		mvc.perform(get("/groups/{id}", group.getId()).with(as(alex)).param("q", "catan"))
			.andExpect(content().string(not(containsString("Closest matches first."))));
	}

	@Test
	void anIsbnFindsTheBookWhateverFormatItIsTypedIn() {
		assertThat(names("0-441-01359-7", null, null)).containsExactly("Dune");
		assertThat(names("978 0441 013593", null, null)).containsExactly("Dune");
	}

	@Test
	void categoryAndTextCombine() {
		assertThat(names(null, "BOOK", null)).containsExactly("Children of Dune", "Dune");
		assertThat(names("c", "GAME", null)).containsExactly("catan");
		assertThat(names("dune", "GAME", null)).isEmpty();
	}

	@Test
	void freeRightNowLeavesOutWhatIsLentOut() {
		Item dune = items.findAll().stream().filter(i -> i.getName().equals("Dune")).findFirst().orElseThrow();
		loanService.lendToOutsider(dune.getId(), sam.getId(), "Grandma", dates.today().plusDays(7));

		assertThat(names("dune", null, "true")).containsExactly("Children of Dune");
		assertThat(names("dune", null, null)).containsExactly("Dune", "Children of Dune");
		GroupService.GroupPage page = groupService.page(group.getId(), alex.getId(),
				ItemFilter.parse(null, null, "true"));
		assertThat(page.lendableCount()).isEqualTo(4);
		assertThat(page.items()).hasSize(3);
	}

	@Test
	void thePageKeepsTheFilterAndCountsMatchesOfAll() throws Exception {
		mvc.perform(get("/groups/{id}", group.getId()).with(as(alex))
			.param("q", "dune")
			.param("category", "BOOK")
			.param("available", "true"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(">Children of Dune</h3>")))
			.andExpect(content().string(containsString(">Dune</h3>")))
			.andExpect(content().string(not(containsString(">Cordless drill</h3>"))))
			.andExpect(content().string(containsString("2 of 4")))
			.andExpect(content().string(containsString("value=\"dune\"")))
			.andExpect(content().string(containsString("<option value=\"BOOK\" selected=\"selected\">")))
			.andExpect(content().string(containsString("name=\"available\" value=\"true\" checked=\"checked\"")))
			.andExpect(content().string(containsString(">Clear</a>")));
	}

	@Test
	void noMatchesIsToldApartFromAnEmptyGroup() throws Exception {
		mvc.perform(get("/groups/{id}", group.getId()).with(as(alex)).param("q", "spaceship"))
			.andExpect(content().string(containsString("Nothing matches your search.")))
			.andExpect(content().string(not(containsString("Nobody in this group has anything to lend yet"))));
	}

	@Test
	void unknownOrMalformedParametersAreIgnoredNotAnError() throws Exception {
		mvc.perform(get("/groups/{id}", group.getId()).with(as(alex))
			.param("category", "SPACESHIP")
			.param("available", "yes"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(">Cordless drill</h3>")))
			.andExpect(content().string(not(containsString(">Clear</a>"))));
	}

	@Test
	void searchingDoesNotLetOutsidersSeeTheGroup() throws Exception {
		AppUser kim = users.findByEmail("kim@example.com").orElseThrow();

		mvc.perform(get("/groups/{id}", group.getId()).with(as(kim)).param("q", "dune"))
			.andExpect(status().isNotFound());
	}

	private static RequestPostProcessor as(AppUser user) {
		return user(AppUserDetails.from(user));
	}
}
