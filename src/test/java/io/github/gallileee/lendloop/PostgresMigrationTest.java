package io.github.gallileee.lendloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gallileee.lendloop.group.AppGroup;
import io.github.gallileee.lendloop.group.GroupForm;
import io.github.gallileee.lendloop.group.GroupService;
import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.loan.Loan;
import io.github.gallileee.lendloop.loan.LoanRepository;
import io.github.gallileee.lendloop.loan.LoanService;
import io.github.gallileee.lendloop.loan.LoanStatus;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.dao.DataIntegrityViolationException;
import io.github.gallileee.lendloop.item.ItemForm;
import io.github.gallileee.lendloop.item.ItemService;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

// The other tests run on H2. This one runs the Flyway migrations and a round trip on real
// PostgreSQL, so SQL that only H2 accepts is caught. Skipped where Docker isn't available;
// CI (GitHub Actions) has Docker, so it always runs there.
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostgresMigrationTest {

	@Autowired
	AppUserRepository users;

	@Autowired
	ItemService itemService;

	@Autowired
	GroupService groupService;

	@Autowired
	LoanService loanService;

	@Autowired
	LoanRepository loans;

	// The loan queries (nulls last, limit, reminders) and the uniqueness constraints on PostgreSQL.
	@Test
	void loansWorkOnPostgres() {
		AppUser owner = users.save(new AppUser("pg-lender@example.com", "Lender", "{noop}irrelevant"));
		AppUser borrower = users.save(new AppUser("pg-borrower@example.com", "Borrower", "{noop}irrelevant"));
		AppUser other = users.save(new AppUser("pg-other@example.com", "Other", "{noop}irrelevant"));
		GroupForm groupForm = new GroupForm();
		groupForm.setName("Loan club");
		AppGroup group = groupService.create(owner.getId(), groupForm);
		groupService.join(group.getInviteToken(), borrower.getId());
		groupService.join(group.getInviteToken(), other.getId());
		ItemForm itemForm = new ItemForm();
		itemForm.setName("Ladder");
		itemForm.setCategory(Category.TOOL);
		Item ladder = itemService.create(owner.getId(), itemForm);

		Loan loan = loanService.request(ladder.getId(), borrower.getId(), "Weekend");
		Loan otherRequest = loanService.request(ladder.getId(), other.getId(), null);
		loanService.approve(loan.getId(), owner.getId(), LocalDate.now().plusDays(7));
		assertThat(loanService.overview(owner.getId()).lentOut()).extracting(Loan::getId).containsExactly(loan.getId());
		assertThat(loanService.overview(owner.getId()).incomingRequests()).hasSize(1);

		// The reminder job's query and its update of the V4 column.
		LocalDate due = loans.findById(loan.getId()).orElseThrow().getDueDate();
		assertThat(loans.findActiveDueBy(due)).extracting(Loan::getId).containsExactly(loan.getId());
		assertThat(loans.findActiveDueBy(due.minusDays(1))).isEmpty();
		loans.recordReminder(loan.getId(), due.minusDays(1));
		assertThat(loans.findById(loan.getId()).orElseThrow().getLastReminderOn()).isEqualTo(due.minusDays(1));

		// Approving the second request directly in the entity, bypassing the service's check,
		// must still be refused by the unique constraint.
		Loan sneaky = loans.findById(otherRequest.getId()).orElseThrow();
		sneaky.approve(LocalDate.now().plusDays(7), Instant.now());
		assertThatThrownBy(() -> loans.saveAndFlush(sneaky)).isInstanceOf(DataIntegrityViolationException.class);

		loanService.markReturned(loan.getId(), owner.getId());
		assertThat(loanService.overview(borrower.getId()).history()).extracting(Loan::getStatus)
			.containsExactly(LoanStatus.RETURNED);

		// V6: a loan to someone outside the app (no borrower row) still shows up in the
		// left-joined queries.
		Loan outside = loanService.lendToOutsider(ladder.getId(), owner.getId(), "Neighbour", LocalDate.now().plusDays(3));
		assertThat(loanService.overview(owner.getId()).lentOut()).extracting(Loan::getBorrowerDisplayName)
			.containsExactly("Neighbour");
		assertThat(loans.findActiveDueBy(LocalDate.now().plusDays(3))).extracting(Loan::getId).contains(outside.getId());
		loanService.markReturned(outside.getId(), owner.getId());
		assertThat(loanService.overview(owner.getId()).history()).extracting(Loan::getBorrowerDisplayName)
			.contains("Neighbour");
	}

	@Test
	void groupQueriesWorkOnPostgres() {
		AppUser owner = users.save(new AppUser("pg-owner@example.com", "Owner", "{noop}irrelevant"));
		AppUser friend = users.save(new AppUser("pg-friend@example.com", "Friend", "{noop}irrelevant"));
		GroupForm groupForm = new GroupForm();
		groupForm.setName("Postgres club");
		AppGroup group = groupService.create(owner.getId(), groupForm);
		groupService.join(group.getInviteToken(), friend.getId());
		ItemForm itemForm = new ItemForm();
		itemForm.setName("Tent");
		itemForm.setCategory(Category.OUTDOOR);
		itemService.create(friend.getId(), itemForm);

		GroupService.GroupPage page = groupService.page(group.getId(), owner.getId());
		assertThat(page.members()).extracting(m -> m.getUser().getDisplayName()).containsExactly("Friend", "Owner");
		assertThat(page.lendableItems()).extracting(Item::getName).containsExactly("Tent");
		assertThat(groupService.membershipsOf(friend.getId())).hasSize(1);
	}

	@Test
	void migrationsMatchTheEntitiesAndDataRoundTrips() {
		AppUser owner = users.save(new AppUser("pg@example.com", "Postgres", "{noop}irrelevant"));
		ItemForm form = new ItemForm();
		form.setName("Cordless drill");
		form.setCategory(Category.TOOL);
		itemService.create(owner.getId(), form);

		assertThat(itemService.listOwnedBy(owner.getId()))
			.singleElement()
			.satisfies(item -> {
				assertThat(item.getName()).isEqualTo("Cordless drill");
				assertThat(item.getCategory()).isEqualTo(Category.TOOL);
				assertThat(item.isAvailable()).isTrue();
			});
	}
}
