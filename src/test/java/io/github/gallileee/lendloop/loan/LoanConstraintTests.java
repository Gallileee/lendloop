package io.github.gallileee.lendloop.loan;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.ItemRepository;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

// The database's last line of defence: what the service checks could still slip through when
// two requests race, so the schema itself must refuse it. These go straight to the repository.
// PostgresMigrationTest repeats the main one on PostgreSQL.
@SpringBootTest
@Transactional
class LoanConstraintTests {

	@Autowired
	AppUserRepository users;

	@Autowired
	ItemRepository items;

	@Autowired
	LoanRepository loans;

	AppUser sam;
	AppUser kim;
	Item catan;

	@BeforeEach
	void setUp() {
		AppUser alex = users.save(new AppUser("alex@example.com", "Alex", "{noop}x"));
		sam = users.save(new AppUser("sam@example.com", "Sam", "{noop}x"));
		kim = users.save(new AppUser("kim@example.com", "Kim", "{noop}x"));
		catan = items.save(new Item(alex, "Catan", null, Category.GAME, true));
	}

	private Loan activeLoan(AppUser borrower) {
		Loan loan = new Loan(catan, borrower, null, Instant.now());
		loan.approve(LocalDate.now().plusDays(7), Instant.now());
		return loan;
	}

	@Test
	void twoActiveLoansForOneItemAreRefused() {
		loans.saveAndFlush(activeLoan(sam));
		assertThatThrownBy(() -> loans.saveAndFlush(activeLoan(kim)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void twoOpenRequestsFromOnePersonForOneItemAreRefused() {
		loans.saveAndFlush(new Loan(catan, sam, null, Instant.now()));
		assertThatThrownBy(() -> loans.saveAndFlush(new Loan(catan, sam, null, Instant.now())))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void closedLoansDontBlockNewOnes() {
		Loan returned = activeLoan(sam);
		returned.markReturned(Instant.now());
		loans.saveAndFlush(returned);
		Loan cancelled = new Loan(catan, kim, null, Instant.now());
		cancelled.cancel(Instant.now());
		loans.saveAndFlush(cancelled);

		assertThatCode(() -> {
			loans.saveAndFlush(activeLoan(sam));
			loans.saveAndFlush(new Loan(catan, kim, null, Instant.now()));
		}).doesNotThrowAnyException();
	}
}
