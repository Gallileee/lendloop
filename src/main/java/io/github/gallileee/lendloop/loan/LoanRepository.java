package io.github.gallileee.lendloop.loan;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

// The fetch joins load what the pages show, because they render after the transaction
// has ended (open-in-view is off).
public interface LoanRepository extends JpaRepository<Loan, Long> {

	@Query("""
			select l from Loan l join fetch l.item i join fetch i.owner join fetch l.borrower
			where l.id = :id""")
	Optional<Loan> findWithParties(Long id);

	// Loans of things I own, in the given statuses.
	@Query("""
			select l from Loan l join fetch l.item i join fetch l.borrower b
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
			select l from Loan l join fetch l.item i join fetch i.owner join fetch l.borrower
			where (i.owner.id = :userId or l.borrower.id = :userId) and l.status in :statuses
			order by l.requestedAt desc
			limit 20""")
	List<Loan> findRecentClosed(Long userId, Collection<LoanStatus> statuses);

	@Query("select l from Loan l join fetch l.borrower where l.item.id in :itemIds and l.status = 'ACTIVE'")
	List<Loan> findActiveForItems(Collection<Long> itemIds);

	@Query("select l.item.id from Loan l where l.borrower.id = :borrowerId and l.status = 'REQUESTED'")
	List<Long> findItemIdsRequestedBy(Long borrowerId);

	boolean existsByItemIdAndStatus(Long itemId, LoanStatus status);

	boolean existsByItemIdAndBorrowerIdAndStatus(Long itemId, Long borrowerId, LoanStatus status);

	@Query("select count(l) from Loan l where l.item.owner.id = :ownerId and l.status = 'REQUESTED'")
	long countOpenRequestsFor(Long ownerId);
}
