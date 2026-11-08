package io.github.gallileee.lendloop.loan;

import io.github.gallileee.lendloop.group.GroupMemberRepository;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.ItemNotFoundException;
import io.github.gallileee.lendloop.item.ItemRepository;
import io.github.gallileee.lendloop.user.AppUserRepository;
import io.github.gallileee.lendloop.web.Dates;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Who may do what with a loan. The Loan entity checks which status changes are possible;
// this service checks that the right person asks for them:
//   borrower: request (needs a shared group, a lendable item that isn't theirs), cancel
//   owner:    approve with a due date, decline, mark returned, lend to someone outside the app
// Anyone else gets LoanNotFoundException (404).
//
// Two writes that race (two requests at once, two approvals of the same item) are stopped by
// the unique constraints from V3__loans.sql. The repository flushes inside each method, so the
// violation surfaces here as a DataIntegrityViolationException and the controller reports it.
@Service
public class LoanService {

	static final int MAX_LOAN_DAYS = 365;
	static final int DEFAULT_LOAN_DAYS = 14;
	static final int MAX_NAME_LENGTH = 60;

	private static final Set<LoanStatus> CLOSED =
			EnumSet.of(LoanStatus.RETURNED, LoanStatus.DECLINED, LoanStatus.CANCELLED);

	private final LoanRepository loans;
	private final ItemRepository items;
	private final AppUserRepository users;
	private final GroupMemberRepository members;
	private final Dates dates;
	private final Clock clock;

	public LoanService(LoanRepository loans, ItemRepository items, AppUserRepository users,
			GroupMemberRepository members, Dates dates, Clock clock) {
		this.loans = loans;
		this.items = items;
		this.users = users;
		this.members = members;
		this.dates = dates;
		this.clock = clock;
	}

	// Everything the loans page shows, from the point of view of one user.
	public record Overview(List<Loan> incomingRequests, List<Loan> lentOut, List<Loan> borrowed,
			List<Loan> myRequests, List<Loan> history) {
	}

	// The dates a return-date input offers: today at the earliest, a suggestion, a year at most.
	public record DueDateRange(LocalDate min, LocalDate suggested, LocalDate max) {
	}

	// What a list of items looks like to one user: which are lent out (and to whom),
	// and which the user has already asked for.
	public record ItemLoanState(Map<Long, Loan> activeByItemId, Set<Long> requestedByMe) {

		public boolean isOnLoan(Long itemId) {
			return activeByItemId.containsKey(itemId);
		}

		public Loan activeLoan(Long itemId) {
			return activeByItemId.get(itemId);
		}

		public boolean isRequestedByMe(Long itemId) {
			return requestedByMe.contains(itemId);
		}
	}

	@Transactional
	public Loan request(Long itemId, Long borrowerId, String message) {
		// The owner is fetched with the item: the controller names them after the transaction ends.
		Item item = items.findWithOwnerById(itemId)
			.orElseThrow(() -> new LoanRuleException("loan.rule.noSuchItem"));
		Long ownerId = item.getOwner().getId();
		if (ownerId.equals(borrowerId)) {
			throw new LoanRuleException("loan.rule.ownItem");
		}
		if (!members.shareAGroup(borrowerId, ownerId)) {
			// Same answer as a missing item: don't reveal things outside the user's groups.
			throw new LoanRuleException("loan.rule.noSuchItem");
		}
		if (!item.isAvailable()) {
			throw new LoanRuleException("loan.rule.notLending");
		}
		if (loans.existsByItemIdAndStatus(itemId, LoanStatus.ACTIVE)) {
			throw new LoanRuleException("loan.rule.someoneHasIt");
		}
		if (loans.existsByItemIdAndBorrowerIdAndStatus(itemId, borrowerId, LoanStatus.REQUESTED)) {
			throw new LoanRuleException("loan.rule.alreadyAsked");
		}
		String note = message == null || message.isBlank() ? null : message.trim();
		if (note != null && note.length() > 300) {
			throw new LoanRuleException("loan.rule.messageTooLong", 300);
		}
		return loans.saveAndFlush(new Loan(item, users.getReferenceById(borrowerId), note, clock.instant()));
	}

	@Transactional
	public Loan approve(Long loanId, Long ownerId, LocalDate dueDate) {
		Loan loan = asOwner(loanId, ownerId);
		requireValidDueDate(dueDate);
		if (loans.existsByItemIdAndStatus(loan.getItem().getId(), LoanStatus.ACTIVE)) {
			throw new LoanRuleException("loan.rule.alreadyLent");
		}
		loan.approve(dueDate, clock.instant());
		loans.flush();
		return loan;
	}

	// The owner hands an item to someone who isn't on Lendloop and notes their name. Works for
	// items that aren't shared with groups too: lending in person doesn't need a group.
	@Transactional
	public Loan lendToOutsider(Long itemId, Long ownerId, String borrowerName, LocalDate dueDate) {
		Item item = items.findByIdAndOwnerId(itemId, ownerId)
			.orElseThrow(() -> new ItemNotFoundException(itemId));
		// Line breaks and tabs become single spaces: the name ends up in email subjects too.
		String name = borrowerName == null ? "" : borrowerName.replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
		if (name.isEmpty()) {
			throw new LoanRuleException("loan.rule.nameRequired");
		}
		if (name.length() > MAX_NAME_LENGTH) {
			throw new LoanRuleException("loan.rule.nameTooLong", MAX_NAME_LENGTH);
		}
		requireValidDueDate(dueDate);
		if (loans.existsByItemIdAndStatus(itemId, LoanStatus.ACTIVE)) {
			throw new LoanRuleException("loan.rule.alreadyLent");
		}
		// "grandma" after an earlier "Grandma" is the same person: keep the spelling already used.
		String known = loans.findOutsiderNamesOf(ownerId).stream()
			.filter(name::equalsIgnoreCase)
			.findFirst()
			.orElse(name);
		return loans.saveAndFlush(Loan.toOutsider(item, known, dueDate, clock.instant()));
	}

	@Transactional
	public Loan decline(Long loanId, Long ownerId) {
		Loan loan = asOwner(loanId, ownerId);
		loan.decline(clock.instant());
		return loan;
	}

	@Transactional
	public Loan markReturned(Long loanId, Long ownerId) {
		Loan loan = asOwner(loanId, ownerId);
		loan.markReturned(clock.instant());
		return loan;
	}

	@Transactional
	public Loan cancel(Long loanId, Long borrowerId) {
		Loan loan = loans.findWithParties(loanId)
			.filter(l -> l.getBorrower().getId().equals(borrowerId))
			.orElseThrow(() -> new LoanNotFoundException(loanId));
		loan.cancel(clock.instant());
		return loan;
	}

	@Transactional(readOnly = true)
	public Overview overview(Long userId) {
		return new Overview(
				loans.findForOwner(userId, EnumSet.of(LoanStatus.REQUESTED)),
				loans.findForOwner(userId, EnumSet.of(LoanStatus.ACTIVE)),
				loans.findForBorrower(userId, EnumSet.of(LoanStatus.ACTIVE)),
				loans.findForBorrower(userId, EnumSet.of(LoanStatus.REQUESTED)),
				loans.findRecentClosed(userId, CLOSED));
	}

	@Transactional(readOnly = true)
	public ItemLoanState stateFor(Collection<Item> shownItems, Long userId) {
		List<Long> itemIds = shownItems.stream().map(Item::getId).toList();
		if (itemIds.isEmpty()) {
			return new ItemLoanState(Map.of(), Set.of());
		}
		Map<Long, Loan> active = loans.findActiveForItems(itemIds).stream()
			.collect(Collectors.toMap(l -> l.getItem().getId(), Function.identity()));
		Set<Long> requested = new HashSet<>(loans.findItemIdsRequestedBy(userId));
		requested.retainAll(itemIds);
		return new ItemLoanState(active, requested);
	}

	@Transactional(readOnly = true)
	public long openRequestsFor(Long ownerId) {
		return loans.countOpenRequestsFor(ownerId);
	}

	// Suggestions for the "To whom" field, so the same person isn't typed differently each time.
	@Transactional(readOnly = true)
	public List<String> outsiderNamesOf(Long ownerId) {
		return loans.findOutsiderNamesOf(ownerId);
	}

	@Transactional(readOnly = true)
	public boolean isLentOut(Long itemId) {
		return loans.existsByItemIdAndStatus(itemId, LoanStatus.ACTIVE);
	}

	public DueDateRange dueDateRange() {
		LocalDate today = dates.today();
		return new DueDateRange(today, today.plusDays(DEFAULT_LOAN_DAYS), today.plusDays(MAX_LOAN_DAYS));
	}

	private void requireValidDueDate(LocalDate dueDate) {
		LocalDate today = dates.today();
		if (dueDate == null || dueDate.isBefore(today) || dueDate.isAfter(today.plusDays(MAX_LOAN_DAYS))) {
			throw new LoanRuleException("loan.rule.dueDate");
		}
	}

	private Loan asOwner(Long loanId, Long ownerId) {
		return loans.findWithParties(loanId)
			.filter(l -> Objects.equals(l.getItem().getOwner().getId(), ownerId))
			.orElseThrow(() -> new LoanNotFoundException(loanId));
	}
}
