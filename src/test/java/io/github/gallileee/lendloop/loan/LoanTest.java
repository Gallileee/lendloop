package io.github.gallileee.lendloop.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.user.AppUser;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

// The status rules on their own, without Spring or a database.
class LoanTest {

	private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
	private static final LocalDate TODAY = LocalDate.parse("2026-11-03");

	private static Loan newRequest() {
		AppUser owner = new AppUser("alex@example.com", "Alex", "{noop}x");
		AppUser borrower = new AppUser("sam@example.com", "Sam", "{noop}x");
		return new Loan(new Item(owner, "Catan", null, Category.GAME, true), borrower, "For Friday", NOW);
	}

	// Brings a fresh request into the given status by the normal path.
	private static Loan loanIn(LoanStatus status) {
		Loan loan = newRequest();
		switch (status) {
			case REQUESTED -> { }
			case ACTIVE -> loan.approve(TODAY.plusDays(14), NOW);
			case RETURNED -> {
				loan.approve(TODAY.plusDays(14), NOW);
				loan.markReturned(NOW);
			}
			case DECLINED -> loan.decline(NOW);
			case CANCELLED -> loan.cancel(NOW);
		}
		return loan;
	}

	@Test
	void aNewLoanIsARequest() {
		Loan loan = newRequest();
		assertThat(loan.getStatus()).isEqualTo(LoanStatus.REQUESTED);
		assertThat(loan.getRequestedAt()).isEqualTo(NOW);
		assertThat(loan.getMessage()).isEqualTo("For Friday");
	}

	@Test
	void theHappyPathIsRequestApproveReturn() {
		Loan loan = newRequest();
		loan.approve(TODAY.plusDays(14), NOW);
		assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
		assertThat(loan.getDueDate()).isEqualTo(TODAY.plusDays(14));
		assertThat(loan.getDecidedAt()).isEqualTo(NOW);

		loan.markReturned(NOW.plusSeconds(60));
		assertThat(loan.getStatus()).isEqualTo(LoanStatus.RETURNED);
		assertThat(loan.getReturnedAt()).isEqualTo(NOW.plusSeconds(60));
	}

	@Test
	void aRequestCanBeDeclinedOrWithdrawn() {
		Loan declined = newRequest();
		declined.decline(NOW);
		assertThat(declined.getStatus()).isEqualTo(LoanStatus.DECLINED);

		Loan cancelled = newRequest();
		cancelled.cancel(NOW);
		assertThat(cancelled.getStatus()).isEqualTo(LoanStatus.CANCELLED);
	}

	// Every status except REQUESTED refuses approve, decline and cancel.
	@ParameterizedTest
	@EnumSource(value = LoanStatus.class, names = "REQUESTED", mode = EnumSource.Mode.EXCLUDE)
	void onlyARequestCanBeAnswered(LoanStatus status) {
		assertRefused(status, l -> l.approve(TODAY.plusDays(7), NOW));
		assertRefused(status, l -> l.decline(NOW));
		assertRefused(status, l -> l.cancel(NOW));
	}

	// Every status except ACTIVE refuses "returned".
	@ParameterizedTest
	@EnumSource(value = LoanStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
	void onlyALentOutItemCanBeReturned(LoanStatus status) {
		assertRefused(status, l -> l.markReturned(NOW));
	}

	@Test
	void overdueMeansActiveAndPastTheDueDate() {
		Loan loan = loanIn(LoanStatus.ACTIVE); // due in 14 days
		assertThat(loan.isOverdueOn(TODAY.plusDays(14))).isFalse();
		assertThat(loan.isOverdueOn(TODAY.plusDays(15))).isTrue();

		loan.markReturned(NOW);
		assertThat(loan.isOverdueOn(TODAY.plusDays(15))).isFalse();
		assertThat(loanIn(LoanStatus.REQUESTED).isOverdueOn(TODAY.plusYears(1))).isFalse();
	}

	@Test
	void onlyRequestsAndActiveLoansAreOpen() {
		assertThat(LoanStatus.values()).filteredOn(LoanStatus::isOpen)
			.containsExactlyInAnyOrder(LoanStatus.REQUESTED, LoanStatus.ACTIVE);
	}

	@Test
	void theDayBeforeTheDueDateGetsADueTomorrowReminder() {
		Loan loan = loanIn(LoanStatus.ACTIVE); // due in 14 days
		LocalDate due = TODAY.plusDays(14);
		assertThat(loan.reminderDueOn(due.minusDays(2))).isEmpty();
		assertThat(loan.reminderDueOn(due.minusDays(1))).contains(ReminderKind.DUE_TOMORROW);
		assertThat(loan.reminderDueOn(due)).isEmpty();
		assertThat(loan.reminderDueOn(due.plusDays(1))).contains(ReminderKind.OVERDUE);
		// Never reminded, so any overdue day qualifies (e.g. the app was down for a while).
		assertThat(loan.reminderDueOn(due.plusDays(5))).contains(ReminderKind.OVERDUE);
	}

	@ParameterizedTest
	@EnumSource(value = LoanStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
	void onlyActiveLoansGetReminders(LoanStatus status) {
		Loan loan = loanIn(status);
		assertThat(loan.reminderDueOn(TODAY.plusDays(13))).isEmpty();
		assertThat(loan.reminderDueOn(TODAY.plusDays(20))).isEmpty();
		assertThat(loan.ownerNoticeDueOn(TODAY.plusDays(20))).isFalse();
	}

	@Test
	void theOwnerIsToldOnlyWhenTheLoanBecomesOverdue() {
		Loan loan = loanIn(LoanStatus.ACTIVE); // due in 14 days, never reminded
		LocalDate due = TODAY.plusDays(14);
		assertThat(loan.ownerNoticeDueOn(due.minusDays(1))).as("due tomorrow").isFalse();
		assertThat(loan.ownerNoticeDueOn(due)).isFalse();
		assertThat(loan.ownerNoticeDueOn(due.plusDays(1))).isTrue();
		// The weekly reminders after the first are covered in LoanReminderTests, which can
		// record a reminder in the database.
	}

	private static void assertRefused(LoanStatus status, Consumer<Loan> action) {
		Loan loan = loanIn(status);
		assertThatThrownBy(() -> action.accept(loan)).isInstanceOf(LoanRuleException.class);
		assertThat(loan.getStatus()).as("status after a refused change").isEqualTo(status);
	}
}
