package io.github.gallileee.lendloop.loan;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Alex owns a board game, Sam is in Alex's group, Kim is in no group with either.
class LoanFlowTests extends WebTest {

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
	AppUser kim;
	AppGroup flat;
	Item catan;

	@BeforeEach
	void setUp() {
		alex = users.save(new AppUser("alex@example.com", "Alex", "{noop}x"));
		sam = users.save(new AppUser("sam@example.com", "Sam", "{noop}x"));
		kim = users.save(new AppUser("kim@example.com", "Kim", "{noop}x"));
		GroupForm form = new GroupForm();
		form.setName("Flat 4B");
		flat = groupService.create(alex.getId(), form);
		groupService.join(flat.getInviteToken(), sam.getId());
		catan = items.save(new Item(alex, "Catan", null, Category.GAME, true));
	}

	// The current row: entities held by the test are detached copies and don't see changes.
	private Loan reload(Loan loan) {
		return loans.findById(loan.getId()).orElseThrow();
	}

	private static RequestPostProcessor as(AppUser user) {
		return user(AppUserDetails.from(user));
	}

	private ResultActions ask(AppUser who, Item item) throws Exception {
		return mvc.perform(post("/loans").with(as(who)).with(csrf())
			.param("itemId", item.getId().toString())
			.param("groupId", flat.getId().toString())
			.param("message", "For Friday's game night"));
	}

	private Loan onlyLoan() {
		assertThat(loans.findAll()).hasSize(1);
		return loans.findAll().get(0);
	}

	private ResultActions approve(AppUser who, Loan loan, LocalDate dueDate) throws Exception {
		return mvc.perform(post("/loans/{id}/approve", loan.getId()).with(as(who)).with(csrf())
			.param("dueDate", dueDate.toString()));
	}

	@Test
	void aGroupMemberAsksAndTheOwnerSeesTheRequest() throws Exception {
		mvc.perform(get("/groups/{id}", flat.getId()).with(as(sam)))
			.andExpect(content().string(containsString("Ask to borrow")));

		ask(sam, catan)
			.andExpect(redirectedUrl("/groups/" + flat.getId()))
			.andExpect(flash().attribute("message", containsString("Asked Alex for “Catan”")));

		Loan loan = onlyLoan();
		assertThat(reload(loan).getStatus()).isEqualTo(LoanStatus.REQUESTED);
		assertThat(loan.getMessage()).isEqualTo("For Friday's game night");

		// The owner gets a badge and the request with its message.
		mvc.perform(get("/loans").with(as(alex)))
			.andExpect(content().string(containsString("waiting for your answer")))
			.andExpect(content().string(containsString("Sam</span> wants to borrow")))
			.andExpect(content().string(containsString("For Friday&#39;s game night")));
		// The group page now says "Requested" instead of offering the button again.
		mvc.perform(get("/groups/{id}", flat.getId()).with(as(sam)))
			.andExpect(content().string(containsString(">Requested<")))
			.andExpect(content().string(not(containsString("Ask to borrow"))));
	}

	@Test
	void requestsThatBreakTheRulesAreRefused() throws Exception {
		Item hidden = items.save(new Item(alex, "Projector", null, Category.ELECTRONICS, false));

		ask(alex, catan).andExpect(flash().attribute("error", "That's your own item."));
		ask(kim, catan).andExpect(flash().attribute("error", "That item doesn't exist."));
		ask(sam, hidden).andExpect(flash().attribute("error", "The owner isn't lending this at the moment."));
		mvc.perform(post("/loans").with(as(sam)).with(csrf()).param("itemId", "999999"))
			.andExpect(flash().attribute("error", "That item doesn't exist."));
		ask(sam, catan);
		ask(sam, catan).andExpect(flash().attribute("error", "You've already asked for this."));

		assertThat(loans.count()).isEqualTo(1);
	}

	@Test
	void approvingLendsTheItemUntilTheDueDate() throws Exception {
		ask(sam, catan);
		Loan loan = onlyLoan();
		LocalDate due = dates.today().plusDays(10);

		approve(alex, loan, due)
			.andExpect(redirectedUrl("/loans"))
			.andExpect(flash().attribute("message", "Lent “Catan” to Sam until " + dates.format(due) + "."));
		assertThat(reload(loan).getStatus()).isEqualTo(LoanStatus.ACTIVE);
		assertThat(reload(loan).getDueDate()).isEqualTo(due);

		mvc.perform(get("/loans").with(as(sam)))
			.andExpect(content().string(containsString("Borrowed by you")))
			.andExpect(content().string(containsString("Due " + dates.format(due))));
		mvc.perform(get("/items").with(as(alex)))
			.andExpect(content().string(containsString("Lent to Sam")));
		mvc.perform(get("/groups/{id}", flat.getId()).with(as(sam)))
			.andExpect(content().string(containsString("You have it")));
	}

	@Test
	void theDueDateMustBeBetweenTodayAndAYearAhead() throws Exception {
		ask(sam, catan);
		Loan loan = onlyLoan();
		String error = "Pick a return date between today and a year from now.";

		approve(alex, loan, dates.today().minusDays(1)).andExpect(flash().attribute("error", error));
		approve(alex, loan, dates.today().plusDays(366)).andExpect(flash().attribute("error", error));
		mvc.perform(post("/loans/{id}/approve", loan.getId()).with(as(alex)).with(csrf()))
			.andExpect(flash().attribute("error", error));
		assertThat(reload(loan).getStatus()).isEqualTo(LoanStatus.REQUESTED);

		approve(alex, loan, dates.today()).andExpect(flash().attribute("message", containsString("Lent")));
	}

	@Test
	void onlyTheOwnerAnswersAndOnlyTheBorrowerWithdraws() throws Exception {
		ask(sam, catan);
		Loan loan = onlyLoan();

		approve(sam, loan, dates.today().plusDays(7)).andExpect(status().isNotFound());
		approve(kim, loan, dates.today().plusDays(7)).andExpect(status().isNotFound());
		mvc.perform(post("/loans/{id}/decline", loan.getId()).with(as(sam)).with(csrf()))
			.andExpect(status().isNotFound());
		mvc.perform(post("/loans/{id}/cancel", loan.getId()).with(as(alex)).with(csrf()))
			.andExpect(status().isNotFound());
		assertThat(reload(loan).getStatus()).isEqualTo(LoanStatus.REQUESTED);

		mvc.perform(post("/loans/{id}/cancel", loan.getId()).with(as(sam)).with(csrf()))
			.andExpect(flash().attribute("message", "Withdrew your request for “Catan”."));
		assertThat(reload(loan).getStatus()).isEqualTo(LoanStatus.CANCELLED);
	}

	@Test
	void anAnsweredRequestCanNoLongerBeChanged() throws Exception {
		ask(sam, catan);
		Loan loan = onlyLoan();
		mvc.perform(post("/loans/{id}/decline", loan.getId()).with(as(alex)).with(csrf()))
			.andExpect(flash().attribute("message", "Declined Sam’s request."));

		approve(alex, loan, dates.today().plusDays(7))
			.andExpect(flash().attribute("error", "This request was already answered or withdrawn."));
		mvc.perform(post("/loans/{id}/cancel", loan.getId()).with(as(sam)).with(csrf()))
			.andExpect(flash().attribute("error", "This request was already answered, so it can't be withdrawn."));
		assertThat(reload(loan).getStatus()).isEqualTo(LoanStatus.DECLINED);
	}

	@Test
	void anItemIsLentToOnePersonAtATime() throws Exception {
		groupService.join(flat.getInviteToken(), kim.getId());
		ask(sam, catan);
		ask(kim, catan);
		Loan samsRequest = loans.findAll().stream().filter(l -> l.getBorrower().getId().equals(sam.getId()))
			.findFirst().orElseThrow();
		Loan kimsRequest = loans.findAll().stream().filter(l -> l.getBorrower().getId().equals(kim.getId()))
			.findFirst().orElseThrow();

		approve(alex, samsRequest, dates.today().plusDays(7));
		approve(alex, kimsRequest, dates.today().plusDays(7))
			.andExpect(flash().attribute("error", "It's already lent out. Mark it as returned first."));
		assertThat(reload(kimsRequest).getStatus()).isEqualTo(LoanStatus.REQUESTED);

		// While it's out, nobody else can ask for it.
		Item drill = items.save(new Item(alex, "Drill", null, Category.TOOL, true));
		ask(kim, drill).andExpect(flash().attribute("message", containsString("Asked Alex")));
		mvc.perform(get("/groups/{id}", flat.getId()).with(as(kim)))
			.andExpect(content().string(containsString("On loan")));
	}

	@Test
	void markingItReturnedClosesTheLoanAndFreesTheItem() throws Exception {
		ask(sam, catan);
		Loan loan = onlyLoan();
		approve(alex, loan, dates.today().plusDays(7));

		mvc.perform(post("/loans/{id}/return", loan.getId()).with(as(sam)).with(csrf()))
			.andExpect(status().isNotFound()); // only the owner confirms it's back
		mvc.perform(post("/loans/{id}/return", loan.getId()).with(as(alex)).with(csrf()))
			.andExpect(flash().attribute("message", "“Catan” is back. Thanks for lending it!"));
		assertThat(reload(loan).getStatus()).isEqualTo(LoanStatus.RETURNED);

		mvc.perform(get("/loans").with(as(sam)))
			.andExpect(content().string(containsString("History")))
			.andExpect(content().string(containsString("Returned")));
		ask(sam, catan).andExpect(flash().attribute("message", containsString("Asked Alex")));
	}

	@Test
	void aLentOutItemCannotBeDeleted() throws Exception {
		ask(sam, catan);
		approve(alex, onlyLoan(), dates.today().plusDays(7));

		mvc.perform(post("/items/{id}/delete", catan.getId()).with(as(alex)).with(csrf()))
			.andExpect(flash().attribute("error", "It's lent out right now. Mark it as returned under Loans first."));
		assertThat(items.findById(catan.getId())).isPresent();
	}

	@Test
	void lateLoansAreMarkedOverdue() throws Exception {
		Loan loan = new Loan(catan, sam, null, Instant.now());
		loan.approve(dates.today().minusDays(3), Instant.now()); // bypasses the service's date check
		loans.save(loan);

		mvc.perform(get("/loans").with(as(alex)))
			.andExpect(content().string(containsString("Overdue since " + dates.format(dates.today().minusDays(3)))));
	}
}
