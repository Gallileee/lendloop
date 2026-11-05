package io.github.gallileee.lendloop.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.gallileee.lendloop.WebTest;
import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.ItemRepository;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// The daily job against the database, with a mock mail server. Extends WebTest only for the
// table cleanup: like production, the job runs without a surrounding transaction.
class LoanReminderTests extends WebTest {

	private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
	private static final LocalDate DUE = LocalDate.parse("2026-11-17");

	@MockitoBean
	JavaMailSender mailSender;

	@Autowired
	LoanReminderJob job;

	@Autowired
	AppUserRepository users;

	@Autowired
	ItemRepository items;

	@Autowired
	LoanRepository loans;

	AppUser alex;
	AppUser sam;
	Loan catan;

	@BeforeEach
	void setUp() {
		alex = users.save(new AppUser("alex@example.com", "Alex", "{noop}x"));
		sam = users.save(new AppUser("sam@example.com", "Sam", "{noop}x"));
		catan = lend(items.save(new Item(alex, "Catan", null, Category.GAME, true)), sam, DUE);
	}

	private Loan lend(Item item, AppUser borrower, LocalDate due) {
		Loan loan = new Loan(item, borrower, null, NOW);
		loan.approve(due, NOW);
		return loans.save(loan);
	}

	private SimpleMailMessage onlyMail() {
		ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender).send(mail.capture());
		clearInvocations(mailSender);
		return mail.getValue();
	}

	private List<SimpleMailMessage> allMail(int count) {
		ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender, times(count)).send(mail.capture());
		clearInvocations(mailSender);
		return mail.getAllValues();
	}

	private void changeSettings(AppUser user, boolean remindBorrowed, boolean notifyLentOverdue) {
		AppUser saved = users.findById(user.getId()).orElseThrow();
		saved.changeSettings(saved.getDisplayName(), remindBorrowed, notifyLentOverdue);
		users.save(saved);
	}

	@Test
	void theBorrowerHearsTheDayBeforeTheDueDate() {
		assertThat(job.sendReminders(DUE.minusDays(2))).isZero();
		verify(mailSender, never()).send(any(SimpleMailMessage.class));

		assertThat(job.sendReminders(DUE.minusDays(1))).isOne();
		SimpleMailMessage mail = onlyMail();
		assertThat(mail.getTo()).containsExactly("sam@example.com");
		assertThat(mail.getFrom()).isEqualTo("Lendloop <no-reply@lendloop.test>");
		assertThat(mail.getSubject()).isEqualTo("“Catan” is due back tomorrow");
		assertThat(mail.getText())
			.startsWith("Hi Sam,")
			.contains("“Catan”, which you borrowed from Alex, is due back tomorrow, 17 Nov 2026.")
			.contains("https://lendloop.test/loans")
			.contains("Turn them off in your settings: https://lendloop.test/settings");
		assertThat(loans.findById(catan.getId()).orElseThrow().getLastReminderOn()).isEqualTo(DUE.minusDays(1));

		// A second run on the same day (e.g. after a restart) sends nothing.
		assertThat(job.sendReminders(DUE.minusDays(1))).isZero();
		verify(mailSender, never()).send(any(SimpleMailMessage.class));
	}

	@Test
	void anOverdueLoanIsRemindedOnTheFirstDayThenWeekly() {
		job.sendReminders(DUE.minusDays(1));
		onlyMail();

		assertThat(job.sendReminders(DUE)).as("the due date itself").isZero();

		assertThat(job.sendReminders(DUE.plusDays(1))).as("borrower and owner").isEqualTo(2);
		List<SimpleMailMessage> mails = allMail(2);
		SimpleMailMessage toSam = mails.get(0);
		assertThat(toSam.getTo()).containsExactly("sam@example.com");
		assertThat(toSam.getSubject()).isEqualTo("“Catan” was due back on 17 Nov 2026");
		assertThat(toSam.getText()).contains("was due back on 17 Nov 2026 (1 day ago). Please give it back to Alex");
		SimpleMailMessage toAlex = mails.get(1);
		assertThat(toAlex.getTo()).containsExactly("alex@example.com");
		assertThat(toAlex.getSubject()).isEqualTo("“Catan” is overdue at Sam");
		assertThat(toAlex.getText())
			.startsWith("Hi Alex,")
			.contains("“Catan”, which you lent to Sam, was due back on 17 Nov 2026 (1 day ago).")
			.contains("https://lendloop.test/settings");

		for (int day = 2; day <= 7; day++) {
			assertThat(job.sendReminders(DUE.plusDays(day))).as("day %d after the due date", day).isZero();
		}
		// The weekly reminder goes to the borrower only; the owner heard once.
		assertThat(job.sendReminders(DUE.plusDays(8))).isOne();
		SimpleMailMessage weekly = onlyMail();
		assertThat(weekly.getTo()).containsExactly("sam@example.com");
		assertThat(weekly.getText()).contains("(8 days ago)");
	}

	@Test
	void aBorrowerWhoTurnedRemindersOffGetsNoneButTheOwnerIsStillTold() {
		changeSettings(sam, false, true);

		assertThat(job.sendReminders(DUE.minusDays(1))).isZero();
		verify(mailSender, never()).send(any(SimpleMailMessage.class));

		assertThat(job.sendReminders(DUE.plusDays(1))).isOne();
		assertThat(onlyMail().getTo()).containsExactly("alex@example.com");
		assertThat(loans.findById(catan.getId()).orElseThrow().getLastReminderOn()).isEqualTo(DUE.plusDays(1));

		assertThat(job.sendReminders(DUE.plusDays(8))).as("no repeat for the owner").isZero();
	}

	@Test
	void anOwnerWhoTurnedNoticesOffIsNotTold() {
		changeSettings(alex, true, false);

		assertThat(job.sendReminders(DUE.plusDays(1))).isOne();
		assertThat(onlyMail().getTo()).containsExactly("sam@example.com");
	}

	@Test
	void aFailedOwnerNoticeIsNotRetriedSoTheBorrowerIsNotRemindedTwice() {
		doThrow(new MailSendException("SMTP server down")).when(mailSender)
			.send(argThat((SimpleMailMessage m) -> m.getTo()[0].equals("alex@example.com")));

		assertThat(job.sendReminders(DUE.plusDays(1))).isOne();
		allMail(2);
		assertThat(loans.findById(catan.getId()).orElseThrow().getLastReminderOn()).isEqualTo(DUE.plusDays(1));

		assertThat(job.sendReminders(DUE.plusDays(2))).isZero();
	}

	@Test
	void returnedLoansAndRequestsGetNothing() {
		Loan loan = loans.findById(catan.getId()).orElseThrow();
		loan.markReturned(NOW);
		loans.save(loan);
		Item book = items.save(new Item(alex, "Dune", null, Category.BOOK, true));
		loans.save(new Loan(book, sam, null, NOW)); // still a request, no due date

		assertThat(job.sendReminders(DUE.minusDays(1))).isZero();
		assertThat(job.sendReminders(DUE.plusDays(1))).isZero();
		verify(mailSender, never()).send(any(SimpleMailMessage.class));
	}

	@Test
	void aFailedEmailIsRetriedNextTimeAndDoesNotStopTheOthers() {
		Item book = items.save(new Item(alex, "Dune", null, Category.BOOK, true));
		Loan dune = lend(book, sam, DUE);
		doThrow(new MailSendException("SMTP server down")).when(mailSender)
			.send(argThat((SimpleMailMessage m) -> m.getSubject().contains("Catan")));

		assertThat(job.sendReminders(DUE.minusDays(1))).isOne();
		verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
		assertThat(loans.findById(catan.getId()).orElseThrow().getLastReminderOn()).isNull();
		assertThat(loans.findById(dune.getId()).orElseThrow().getLastReminderOn()).isEqualTo(DUE.minusDays(1));
	}

	@Test
	void namesCannotAddMailHeaders() {
		Item sneaky = items.save(new Item(alex, "Catan\r\nBcc: everyone@example.com", null, Category.GAME, true));
		lend(sneaky, sam, DUE.minusDays(5)); // Catan is not due yet that day

		job.sendReminders(DUE.minusDays(6));
		SimpleMailMessage mail = onlyMail();
		assertThat(mail.getSubject()).isEqualTo("“Catan Bcc: everyone@example.com” is due back tomorrow");
		assertThat(mail.getBcc()).isNull();
	}

	// Alex lends Dune to Grandma (not on Lendloop), due 12 Nov; Catan (Sam, 17 Nov) goes back
	// first so its own emails don't mix in.
	private Loan duneLentToGrandma() {
		Loan returned = loans.findById(catan.getId()).orElseThrow();
		returned.markReturned(NOW);
		loans.save(returned);
		Item book = items.save(new Item(alex, "Dune", null, Category.BOOK, true));
		return loans.save(Loan.toOutsider(book, "Grandma", DUE.minusDays(5), NOW));
	}

	@Test
	void forSomeoneOutsideTheAppTheOwnerGetsTheRemindersInstead() {
		Loan dune = duneLentToGrandma();
		LocalDate due = dune.getDueDate();

		assertThat(job.sendReminders(due.minusDays(1))).isOne();
		SimpleMailMessage tomorrow = onlyMail();
		assertThat(tomorrow.getTo()).containsExactly("alex@example.com");
		assertThat(tomorrow.getSubject()).isEqualTo("“Dune” is due back from Grandma tomorrow");
		assertThat(tomorrow.getText())
			.startsWith("Hi Alex,")
			.contains("“Dune”, which you lent to Grandma, is due back tomorrow, 12 Nov 2026.")
			.contains("Grandma isn't on Lendloop, so this reminder comes to you.")
			.contains("https://lendloop.test/settings");

		// One email on the first late day, not a reminder plus a separate owner notice.
		assertThat(job.sendReminders(due.plusDays(1))).isOne();
		SimpleMailMessage late = onlyMail();
		assertThat(late.getTo()).containsExactly("alex@example.com");
		assertThat(late.getSubject()).isEqualTo("“Dune” is overdue at Grandma");
		assertThat(late.getText())
			.contains("“Dune”, which you lent to Grandma, was due back on 12 Nov 2026 (1 day ago).")
			.contains("you'll get this once a week");

		for (int day = 2; day <= 7; day++) {
			assertThat(job.sendReminders(due.plusDays(day))).as("day %d after the due date", day).isZero();
		}
		// Unlike in-app loans, the owner keeps getting the weekly reminder.
		assertThat(job.sendReminders(due.plusDays(8))).isOne();
		assertThat(onlyMail().getText()).contains("(8 days ago)");
		assertThat(loans.findById(dune.getId()).orElseThrow().getLastReminderOn()).isEqualTo(due.plusDays(8));
	}

	@Test
	void anOwnerWhoTurnedNoticesOffGetsNoRemindersForOutsideLoans() {
		Loan dune = duneLentToGrandma();
		changeSettings(alex, true, false);

		assertThat(job.sendReminders(dune.getDueDate().minusDays(1))).isZero();
		assertThat(job.sendReminders(dune.getDueDate().plusDays(1))).isZero();
		verify(mailSender, never()).send(any(SimpleMailMessage.class));
		// Still recorded, so turning it back on doesn't send a backlog the same day.
		assertThat(loans.findById(dune.getId()).orElseThrow().getLastReminderOn())
			.isEqualTo(dune.getDueDate().plusDays(1));
	}

	@Test
	void aFailedOwnerReminderForAnOutsideLoanIsRetried() {
		Loan dune = duneLentToGrandma();
		LocalDate tomorrowDay = dune.getDueDate().minusDays(1);
		doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(SimpleMailMessage.class));

		assertThat(job.sendReminders(tomorrowDay)).isZero();
		assertThat(loans.findById(dune.getId()).orElseThrow().getLastReminderOn()).isNull();

		// Only the owner is emailed for this loan, so retrying can't remind anyone twice.
		reset(mailSender);
		assertThat(job.sendReminders(tomorrowDay)).isOne();
		assertThat(onlyMail().getSubject()).isEqualTo("“Dune” is due back from Grandma tomorrow");
	}
}
