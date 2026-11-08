package io.github.gallileee.lendloop.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;

import io.github.gallileee.lendloop.WebTest;
import io.github.gallileee.lendloop.group.AppGroup;
import io.github.gallileee.lendloop.group.GroupForm;
import io.github.gallileee.lendloop.group.GroupService;
import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.ItemRepository;
import io.github.gallileee.lendloop.loan.LoanRepository;
import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// The signed-in pages and the messages after an action, with the language cookie set to Czech.
// The same pages in English are covered by the feature tests (they send no language at all).
// Alex owns Catan and the group Flat 4B, Sam is a member.
class CzechPagesTests extends WebTest {

	private static final Cookie CZECH = new Cookie(LanguageResolver.COOKIE, "cs");

	@Autowired
	AppUserRepository users;

	@Autowired
	ItemRepository items;

	@Autowired
	LoanRepository loans;

	@Autowired
	GroupService groupService;

	@Autowired
	Dates dates;

	AppUser alex;
	AppUser sam;
	AppGroup flat;
	Item catan;

	@BeforeEach
	void setUp() {
		alex = users.save(new AppUser("alex@example.com", "Alex", "{noop}x"));
		sam = users.save(new AppUser("sam@example.com", "Sam", "{noop}x"));
		GroupForm form = new GroupForm();
		form.setName("Flat 4B");
		flat = groupService.create(alex.getId(), form);
		groupService.join(flat.getInviteToken(), sam.getId());
		catan = items.save(new Item(alex, "Catan", null, Category.GAME, true));
	}

	private static RequestPostProcessor as(AppUser user) {
		return user(AppUserDetails.from(user));
	}

	private ResultActions czechGet(AppUser who, String url, Object... vars) throws Exception {
		return mvc.perform(get(url, vars).with(as(who)).cookie(CZECH));
	}

	private ResultActions ask(AppUser who) throws Exception {
		return mvc.perform(post("/loans").with(as(who)).with(csrf()).cookie(CZECH)
			.param("itemId", catan.getId().toString())
			.param("groupId", flat.getId().toString()));
	}

	@Test
	void myThingsIsCzechIncludingTheCategories() throws Exception {
		czechGet(alex, "/items")
			.andExpect(content().string(allOf(containsString("<html lang=\"cs\""),
					containsString("<title>Moje věci · LendLoop</title>"), containsString("Přidat věc"),
					containsString("Desková hra"), containsString("Dá se půjčit"), containsString("Smazat Catan?"),
					not(containsString("Add item")), not(containsString("Board game")))));

		czechGet(alex, "/items/new")
			.andExpect(content().string(allOf(containsString("Najděte ji podle ISBN"), containsString(">Kniha<"),
					containsString(">Ostatní<"), not(containsString(">Book<")))));
	}

	@Test
	void theIsbnLookupAnswersInCzech() throws Exception {
		czechGet(alex, "/items/new?isbn=123")
			.andExpect(content().string(containsString("To není platné ISBN.")));
	}

	@Test
	void theGroupPageIsCzechForMembersAndTheOwner() throws Exception {
		czechGet(sam, "/groups/{id}", flat.getId())
			.andExpect(content().string(allOf(containsString("Co si můžete půjčit"),
					containsString("Požádat o půjčení"), containsString("půjčuje Alex"), containsString("Členové"),
					containsString("Opustit skupinu Flat 4B?"), not(containsString("Ask to borrow")))));

		// The owner also sees the invite (with a Czech date and time) and the group settings.
		czechGet(alex, "/groups/{id}", flat.getId())
			.andExpect(content().string(allOf(containsString("Pozvěte kamarády"), containsString("Kopírovat"),
					containsString("Nastavení skupiny"), containsString("Pro potvrzení napište"),
					matchesPattern("(?s).*<time [^>]*>\\d{1,2}\\. \\d{1,2}\\. \\d{4} \\d{1,2}:\\d{2}</time>.*"))));
	}

	@Test
	void flashMessagesFollowThePageLanguage() throws Exception {
		ask(sam).andExpect(flash().attribute("message",
				"Žádost o „Catan“ je odeslaná (Alex). Odpověď uvidíte v Půjčkách."));
		// A rule the service checks...
		ask(sam).andExpect(flash().attribute("error", "O tuto věc už jste požádali."));
		ask(alex).andExpect(flash().attribute("error", "To je vaše vlastní věc."));

		// ...and one the entity checks, with a Czech date in the success message.
		Long loanId = loans.findAll().get(0).getId();
		LocalDate due = dates.today().plusDays(7);
		mvc.perform(post("/loans/{id}/approve", loanId).with(as(alex)).with(csrf()).cookie(CZECH)
				.param("dueDate", due.toString()))
			.andExpect(flash().attribute("message",
					"„Catan“ je půjčené: Sam, vrátit do " + DateTimeFormatter.ofPattern("d. M. yyyy").format(due) + "."));
		mvc.perform(post("/loans/{id}/decline", loanId).with(as(alex)).with(csrf()).cookie(CZECH))
			.andExpect(flash().attribute("error", "Na tuto žádost už někdo odpověděl, nebo byla stažená."));
	}

	@Test
	void groupActionsAnswerInCzech() throws Exception {
		mvc.perform(post("/groups/{id}/rename", flat.getId()).with(as(alex)).with(csrf()).cookie(CZECH)
				.param("name", "Byt 5C"))
			.andExpect(flash().attribute("message", "Skupina se teď jmenuje Byt 5C."));
		mvc.perform(post("/groups/{id}/members/{userId}/remove", flat.getId(), alex.getId())
				.with(as(alex)).with(csrf()).cookie(CZECH))
			.andExpect(flash().attribute("error", "Sami sebe odebrat nemůžete. Nejdřív skupinu předejte, nebo ji smažte."));
	}

	@Test
	void theLoansPageIsCzech() throws Exception {
		ask(sam);
		czechGet(alex, "/loans")
			.andExpect(content().string(allOf(containsString("Čeká na vaši odpověď"),
					containsString("Sam si chce půjčit Catan"), containsString("Vrátit do"),
					not(containsString("Waiting for your answer")))));
		czechGet(sam, "/loans")
			.andExpect(content().string(allOf(containsString("Vaše žádosti"), containsString("žádost pro: Alex"))));
	}

	@Test
	void theSettingsPageIsCzech() throws Exception {
		czechGet(alex, "/settings")
			.andExpect(content().string(allOf(containsString("<h1>Nastavení</h1>"), containsString("Změna hesla"),
					containsString("Odhlásit všude jinde"), not(containsString("Change password")))));
	}
}
