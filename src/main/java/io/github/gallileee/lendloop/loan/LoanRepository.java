package io.github.gallileee.lendloop.loan;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

// The fetch joins load what the pages show, because they render after the transaction
// has ended (open-in-view is off). The borrower is a LEFT join: a loan to someone outside
// the app has no borrower row and an inner join would drop it.
public interface LoanRepository extends JpaRepository<Loan, Long> {

	@Query("""
			select l from Loan l join fetch l.item i join fetch i.owner left join fetch l.borrower
			where l.id = :id""")
	Optional<Loan> findWithParties(Long id);

	// Loans of things I own, in the given statuses.
	@Query("""
			select l from Loan l join fetch l.item i left join fetch l.borrower b
			where i.owner.id = :ownerId and l.status in :statuses
			order by l.dueDate nulls last, l.requestedAt""")
	List<Loan> findForOwner(Long ownerId, Collection<LoanStatus> statuses);

	// Loans where I'm the borrower, in the given statuses.
	@Query("""
			select l from Loan l join fetch l.item i join fetch i.owner
			where l.borrower.id = :borrowerId and l.status in :statuses
			order by l.dueDate nulls last, l.requestedAt""")
	List<Loan> findForBorrower(Long borrowerId, Collection<LoanStatus> statuses);

	// Finished loans I was part of, newest first.
	@Query("""
			select l from Loan l join fetch l.item i join fetch i.owner left join fetch l.borrower b
			where (i.owner.id = :userId or b.id = :userId) and l.status in :statuses
			order by l.requestedAt desc
			limit 20""")
	List<Loan> findRecentClosed(Long userId, Collection<LoanStatus> statuses);

	@Query("select l from Loan l left join fetch l.borrower where l.item.id in :itemIds and l.status = 'ACTIVE'")
	List<Loan> findActiveForItems(Collection<Long> itemIds);

	@Query("select l.item.id from Loan l where l.borrower.id = :borrowerId and l.status = 'REQUESTED'")
	List<Long> findItemIdsRequestedBy(Long borrowerId);

	boolean existsByItemIdAndStatus(Long itemId, LoanStatus status);

	boolean existsByItemIdAndBorrowerIdAndStatus(Long itemId, Long borrowerId, LoanStatus status);

	// Active loans that may need a reminder: due by the given day (tomorrow) or earlier.
	// Loan.reminderDueOn decides which of them actually get one.
	@Query("""
			select l from Loan l join fetch l.item i join fetch i.owner left join fetch l.borrower
			where l.status = 'ACTIVE' and l.dueDate <= :dueBy
			order by l.dueDate, l.id""")
	List<Loan> findActiveDueBy(LocalDate dueBy);

	// Only touches the reminder column, so it can't overwrite a status change (e.g. "returned")
	// made while the email was being sent.
	@Transactional
	@Modifying
	@Query("update Loan l set l.lastReminderOn = :day where l.id = :id")
	void recordReminder(Long id, LocalDate day);

	// Names of people outside the app this owner has lent to, most recently used first.
	@Query("""
			select l.borrowerName from Loan l
			where l.item.owner.id = :ownerId and l.borrowerName is not null
			group by l.borrowerName
			order by max(l.requestedAt) desc
			limit 50""")
	List<String> findOutsiderNamesOf(Long ownerId);

	@Query("select count(l) from Loan l where l.item.owner.id = :ownerId and l.status = 'REQUESTED'")
	long countOpenRequestsFor(Long ownerId);
}
