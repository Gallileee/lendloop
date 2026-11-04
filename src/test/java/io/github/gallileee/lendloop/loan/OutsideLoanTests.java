package io.github.gallileee.lendloop.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import io.github.gallileee.lendloop.web.Dates;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Lending to someone who isn't on Lendloop. Alex owns a board game; Sam is in Alex's group.
class OutsideLoanTests extends WebTest {

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

	private ResultActions lend(AppUser who, Item item, String name, LocalDate due) throws Exception {
		var request = post("/loans/outside").with(as(who)).with(csrf()).param("itemId", item.getId().toString());
		if (name != null) {
			request.param("borrowerName", name);
		}
		if (due != null) {
			request.param("dueDate", due.toString());
		}
		return mvc.perform(request);
	}

	@Test
	void theOwnerLendsToSomeoneOutsideTheApp() throws Exception {
		LocalDate due = dates.today().plusDays(14);
		mvc.perform(get("/items").with(as(alex)))
			.andExpect(content().string(containsString("action=\"/loans/outside\"")))
			.andExpect(content().string(containsString("value=\"" + due + "\"")));

		lend(alex, catan, "  Grandma\n  Jana ", due)
			.andExpect(redirectedUrl("/items"))
			.andExpect(flash().attribute("message", "Lent “Catan” to Grandma Jana until " + dates.format(due) + "."));

		Loan loan = loans.findAll().get(0);
		assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
		assertThat(loan.isOutsideApp()).isTrue();
		assertThat(loan.getDueDate()).isEqualTo(due);

		// My things: lent out, and no second "Lend" form for it.
		mvc.perform(get("/items").with(as(alex)))
			.andExpect(content().string(containsString("Lent to Grandma Jana")))
			.andExpect(content().string(not(containsString("action=\"/loans/outside\""))));
		// Loans page: under "Lent out" with a hint that they're not on the app.
		mvc.perform(get("/loans").with(as(alex)))
			.andExpect(content().string(containsString("with Grandma Jana")))
			.andExpect(content().string(containsString("Not on Lendloop")));
		// Group members only see that it's out, not to whom.
		mvc.perform(get("/groups/{id}", flat.getId()).with(as(sam)))
			.andExpect(content().string(containsString(">On loan<")))
			.andExpect(content().string(not(containsString("Grandma"))));
		mvc.perform(get("/groups/{id}", flat.getId()).with(as(alex)))
			.andExpect(content().string(containsString("Lent to Grandma Jana")));
	}

	@Test
	void itComesBackLikeAnyOtherLoan() throws Exception {
		lend(alex, catan, "Grandma", dates.today().plusDays(3));
		Loan loan = loans.findAll().get(0);

		mvc.perform(post("/loans/{id}/return", loan.getId()).with(as(alex)).with(csrf()))
			.andExpect(flash().attribute("message", "“Catan” is back. Thanks for lending it!"));
		assertThat(loans.findById(loan.getId()).orElseThrow().getStatus()).isEqualTo(LoanStatus.RETURNED);

		mvc.perform(get("/loans").with(as(alex)))
			.andExpect(content().string(containsString("to Grandma")))
			.andExpect(content().string(containsString(">Returned<")));
		// And it can be lent again.
		lend(alex, catan, "Petr", dates.today().plusDays(3))
			.andExpect(flash().attribute("message", containsString("Lent “Catan” to Petr")));
	}

	@Test
	void badInputIsRefusedWithAMessage() throws Exception {
		LocalDate due = dates.today().plusDays(7);
		lend(alex, catan, "   ", due).andExpect(flash().attribute("error", "Write who you're lending it to."));
		lend(alex, catan, null, due).andExpect(flash().attribute("error", "Write who you're lending it to."));
		lend(alex, catan, "x".repeat(61), due)
			.andExpect(flash().attribute("error", "Keep the name under 60 characters."));
		String dateError = "Pick a return date between today and a year from now.";
		lend(alex, catan, "Grandma", null).andExpect(flash().attribute("error", dateError));
		lend(alex, catan, "Grandma", dates.today().minusDays(1)).andExpect(flash().attribute("error", dateError));
		lend(alex, catan, "Grandma", dates.today().plusDays(366)).andExpect(flash().attribute("error", dateError));
		assertThat(loans.count()).isZero();

		lend(alex, catan, "x".repeat(60), due).andExpect(flash().attribute("message", containsString("Lent")));
	}

	@Test
	void anItemThatIsOutCannotBeLentTwice() throws Exception {
		LocalDate due = dates.today().plusDays(7);
		lend(alex, catan, "Grandma", due);
		lend(alex, catan, "Petr", due)
			.andExpect(flash().attribute("error", "It's already lent out. Mark it as returned first."));

		// A group member's request that was waiting can't be approved while it's out either.
		AppUser kim = users.save(new AppUser("kim@example.com", "Kim", "{noop}x"));
		groupService.join(flat.getInviteToken(), kim.getId());
		Loan request = loans.save(new Loan(catan, kim, null, Instant.now()));
		mvc.perform(post("/loans/{id}/approve", request.getId()).with(as(alex)).with(csrf())
				.param("dueDate", due.toString()))
			.andExpect(flash().attribute("error", "It's already lent out. Mark it as returned first."));
		assertThat(loans.count()).isEqualTo(2);
	}

	@Test
	void onlyTheOwnerCanLendTheirItem() throws Exception {
		lend(sam, catan, "Grandma", dates.today().plusDays(7)).andExpect(status().isNotFound());
		mvc.perform(post("/loans/outside").with(as(alex)).with(csrf()).param("itemId", "999999")
				.param("borrowerName", "Grandma").param("dueDate", dates.today().toString()))
			.andExpect(status().isNotFound());
		assertThat(loans.count()).isZero();
	}

	@Test
	void theDatabaseNeedsExactlyOneBorrower() {
		// Neither an app user nor a name.
		assertThatThrownBy(() -> jdbc.update("""
				insert into loan (item_id, status, due_date, requested_at)
				values (?, 'ACTIVE', current_date, current_timestamp)""", catan.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
		// Both.
		assertThatThrownBy(() -> jdbc.update("""
				insert into loan (item_id, borrower_id, borrower_name, status, due_date, requested_at)
				values (?, ?, 'Grandma', 'ACTIVE', current_date, current_timestamp)""", catan.getId(), sam.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
		// A request from someone outside the app: nobody could answer it.
		assertThatThrownBy(() -> jdbc.update("""
				insert into loan (item_id, borrower_name, status, requested_at)
				values (?, 'Grandma', 'REQUESTED', current_timestamp)""", catan.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
