package io.github.gallileee.lendloop.loan;

import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

// One request to borrow one item, and what became of it. The status only changes through
// the transition methods below, each of which checks that it is allowed from the current
// status. Who may call which one (owner or borrower) is checked in LoanService.
@Entity
public class Loan {

	static final int OVERDUE_REMINDER_DAYS = 7;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "item_id", nullable = false)
	private Item item;

	// An app user, or null when the item went to someone outside the app (borrowerName).
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "borrower_id")
	private AppUser borrower;

	// The name the owner typed for someone who isn't on Lendloop; null for app users.
	@Column(name = "borrower_name")
	private String borrowerName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private LoanStatus status;

	private String message;

	@Column(name = "due_date")
	private LocalDate dueDate;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt;

	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(name = "returned_at")
	private Instant returnedAt;

	@Column(name = "last_reminder_on")
	private LocalDate lastReminderOn;

	// Copies of the ids that back the database's uniqueness rules (see V3__loans.sql).
	// Kept in sync with the status by syncUniquenessKeys(); never read by the app.
	@Column(name = "active_item_id")
	private Long activeItemId;

	@Column(name = "open_request_item_id")
	private Long openRequestItemId;

	@Column(name = "open_request_borrower_id")
	private Long openRequestBorrowerId;

	protected Loan() {
		// for JPA
	}

	public Loan(Item item, AppUser borrower, String message, Instant now) {
		this.item = item;
		this.borrower = borrower;
		this.message = message;
		this.status = LoanStatus.REQUESTED;
		this.requestedAt = now;
		syncUniquenessKeys();
	}

	// A loan to someone outside the app. Nobody can answer a request, so it starts ACTIVE.
	public static Loan toOutsider(Item item, String borrowerName, LocalDate dueDate, Instant now) {
		Loan loan = new Loan();
		loan.item = item;
		loan.borrowerName = borrowerName;
		loan.dueDate = dueDate;
		loan.requestedAt = now;
		loan.decidedAt = now;
		loan.status = LoanStatus.ACTIVE;
		loan.syncUniquenessKeys();
		return loan;
	}

	public void approve(LocalDate dueDate, Instant now) {
		requireStatus(LoanStatus.REQUESTED, "loan.rule.alreadyAnswered");
		this.dueDate = dueDate;
		this.decidedAt = now;
		moveTo(LoanStatus.ACTIVE);
	}

	public void decline(Instant now) {
		requireStatus(LoanStatus.REQUESTED, "loan.rule.alreadyAnswered");
		this.decidedAt = now;
		moveTo(LoanStatus.DECLINED);
	}

	public void cancel(Instant now) {
		requireStatus(LoanStatus.REQUESTED, "loan.rule.cannotWithdraw");
		this.decidedAt = now;
		moveTo(LoanStatus.CANCELLED);
	}

	public void markReturned(Instant now) {
		requireStatus(LoanStatus.ACTIVE, "loan.rule.notLentOut");
		this.returnedAt = now;
		moveTo(LoanStatus.RETURNED);
	}

	public boolean isOutsideApp() {
		return borrower == null;
	}

	public boolean isBorrowedBy(Long userId) {
		return borrower != null && borrower.getId().equals(userId);
	}

	// Who has (or asked for) the item, for pages and emails.
	public String getBorrowerDisplayName() {
		return borrower != null ? borrower.getDisplayName() : borrowerName;
	}

	public boolean isOverdueOn(LocalDate today) {
		return status == LoanStatus.ACTIVE && dueDate.isBefore(today);
	}

	// Which email the borrower should get today, if any:
	//   the day before the due date: "due tomorrow";
	//   the first day it's overdue, then every OVERDUE_REMINDER_DAYS days: "overdue".
	// lastReminderOn makes a second run on the same day send nothing.
	public Optional<ReminderKind> reminderDueOn(LocalDate today) {
		if (status != LoanStatus.ACTIVE || today.equals(lastReminderOn)) {
			return Optional.empty();
		}
		if (today.equals(dueDate.minusDays(1))) {
			return Optional.of(ReminderKind.DUE_TOMORROW);
		}
		if (isOverdueOn(today)) {
			boolean remindedSinceOverdue = lastReminderOn != null && lastReminderOn.isAfter(dueDate);
			boolean weekPassed = lastReminderOn != null
					&& !lastReminderOn.isAfter(today.minusDays(OVERDUE_REMINDER_DAYS));
			if (!remindedSinceOverdue || weekPassed) {
				return Optional.of(ReminderKind.OVERDUE);
			}
		}
		return Optional.empty();
	}

	// Whether the owner should hear today that the loan is overdue: once, with the borrower's
	// first overdue reminder, not with the weekly ones after it.
	public boolean ownerNoticeDueOn(LocalDate today) {
		boolean firstOverdueReminder = lastReminderOn == null || !lastReminderOn.isAfter(dueDate);
		return reminderDueOn(today).filter(ReminderKind.OVERDUE::equals).isPresent() && firstOverdueReminder;
	}

	private void requireStatus(LoanStatus expected, String messageCode) {
		if (status != expected) {
			throw new LoanRuleException(messageCode);
		}
	}

	private void moveTo(LoanStatus next) {
		this.status = next;
		syncUniquenessKeys();
	}

	private void syncUniquenessKeys() {
		Long itemId = item.getId();
		activeItemId = status == LoanStatus.ACTIVE ? itemId : null;
		openRequestItemId = status == LoanStatus.REQUESTED ? itemId : null;
		openRequestBorrowerId = status == LoanStatus.REQUESTED ? borrower.getId() : null;
	}

	public Long getId() {
		return id;
	}

	public Item getItem() {
		return item;
	}

	// Null for a loan to someone outside the app; see isOutsideApp().
	public AppUser getBorrower() {
		return borrower;
	}

	public LoanStatus getStatus() {
		return status;
	}

	public String getMessage() {
		return message;
	}

	public LocalDate getDueDate() {
		return dueDate;
	}

	public Instant getRequestedAt() {
		return requestedAt;
	}

	public Instant getDecidedAt() {
		return decidedAt;
	}

	public Instant getReturnedAt() {
		return returnedAt;
	}

	public LocalDate getLastReminderOn() {
		return lastReminderOn;
	}
}
