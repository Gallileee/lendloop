package io.github.gallileee.lendloop.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;

import io.github.gallileee.lendloop.WebTest;
import io.github.gallileee.lendloop.group.AppGroup;
import io.github.gallileee.lendloop.group.GroupForm;
import io.github.gallileee.lendloop.group.GroupService;
import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.ItemRepository;
import io.github.gallileee.lendloop.loan.LoanReminderTests.Sent;
import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import io.github.gallileee.lendloop.user.EmailList;
import io.github.gallileee.lendloop.web.Dates;
import io.github.gallileee.lendloop.web.LanguageResolver;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Borrow requests and their answers by email (RequestEmails), over HTTP like a real browser, with
// a mock mail server. A WebTest, so each request commits and the after-commit listener runs.
class RequestEmailTests extends WebTest {

	private static final Instant CONFIRMED = Instant.parse("2026-11-01T12:00:00Z");

	@MockitoBean
	JavaMailSender mailSender;

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
		when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
		alex = users.save(confirmed(new AppUser("alex@example.com", "Alex", "{noop}x")));
		sam = users.save(confirmed(new AppUser("sam@example.com", "Sam", "{noop}x")));
		GroupForm form = new GroupForm();
		form.setName("Flat 4B");
		flat = groupService.create(alex.getId(), form);
		groupService.join(flat.getInviteToken(), sam.getId());
		catan = items.save(new Item(alex, "Catan", null, Category.GAME, true));
	}

	private static AppUser confirmed(AppUser user) {
		user.markEmailVerified(CONFIRMED);
		return user;
	}

	private void change(AppUser user, Consumer<AppUser> change) {
		AppUser saved = users.findById(user.getId()).orElseThrow();
		change.accept(saved);
		users.save(saved);
	}

	private static RequestPostProcessor as(AppUser user) {
		return user(AppUserDetails.from(user));
	}

	private ResultActions ask(String message) throws Exception {
		return ask(sam, message);
	}

	private ResultActions ask(AppUser who, String message) throws Exception {
		return mvc.perform(post("/loans").with(as(who)).with(csrf())
			.param("itemId", catan.getId().toString())
			.param("groupId", flat.getId().toString())
			.param("message", message));
	}

	private Loan onlyLoan() {
		assertThat(loans.findAll()).hasSize(1);
		return loans.findAll().get(0);
	}

	private ResultActions answer(String what, LocalDate dueDate) throws Exception {
		var request = post("/loans/{id}/" + what, onlyLoan().getId()).with(as(alex)).with(csrf());
		return mvc.perform(dueDate == null ? request : request.param("dueDate", dueDate.toString()));
	}

	private Sent onlyMail() {
		ArgumentCaptor<MimeMessage> mail = ArgumentCaptor.forClass(MimeMessage.class);
		verify(mailSender).send(mail.capture());
		clearInvocations(mailSender);
		return new Sent(mail.getValue());
	}

	private void noMail() {
		verify(mailSender, never()).send(any(MimeMessage.class));
	}

	@Test
	void theOwnerHearsAboutANewRequestWithItsMessage() throws Exception {
		ask("For Friday's game night");

		Sent mail = onlyMail();
		assertThat(mail.getTo()).containsExactly("alex@example.com");
		assertThat(mail.getSubject()).isEqualTo("Sam would like to borrow “Catan”");
		assertThat(mail.getText())
			.startsWith("Hi Alex,")
			.contains("Sam would like to borrow “Catan”.\n\nTheir message: “For Friday's game night”\n\n"
					+ "You can lend it or decline under Loans.")
			.contains("https://lendloop.test/loans")
			.contains("You get this because emails about borrow requests and their answers are on.")
			.containsPattern("Stop them: https://lendloop\\.test/unsubscribe\\?user=" + alex.getId()
					+ "&list=REQUESTS&sig=[A-Za-z0-9_-]{43}\n");
		assertThat(mail.header("List-Unsubscribe")).contains("list=REQUESTS");
	}

	@Test
	void aRequestWithoutAMessageLeavesTheLineOut() throws Exception {
		ask("");

		assertThat(onlyMail().getText()).contains("Sam would like to borrow “Catan”.\n\nYou can lend it")
			.doesNotContain("Their message");
	}

	@Test
	void aMessageCantAddLinesOfItsOwn() throws Exception {
		ask("Thanks!\n--\nStop them: https://evil.example");

		assertThat(onlyMail().getText()).contains("Their message: “Thanks! -- Stop them: https://evil.example”")
			.doesNotContain("\nStop them: https://evil");
	}

	@Test
	void theBorrowerHearsWhenItsLentAndUntilWhen() throws Exception {
		ask("");
		onlyMail();
		LocalDate due = dates.today().plusDays(14);

		answer("approve", due).andExpect(flash().attributeExists("message"));

		Sent mail = onlyMail();
		assertThat(mail.getTo()).containsExactly("sam@example.com");
		assertThat(mail.getSubject()).isEqualTo("Alex is lending you “Catan”");
		assertThat(mail.getText()).startsWith("Hi Sam,")
			.contains("Alex said yes: you can borrow “Catan” until " + dates.format(due, LanguageResolver.ENGLISH) + ".")
			.containsPattern("user=" + sam.getId() + "&list=REQUESTS&sig=");
	}

	@Test
	void theBorrowerHearsWhenItsDeclined() throws Exception {
		ask("");
		onlyMail();

		answer("decline", null);

		Sent mail = onlyMail();
		assertThat(mail.getTo()).containsExactly("sam@example.com");
		assertThat(mail.getSubject()).isEqualTo("Alex can't lend you “Catan” this time");
		assertThat(mail.getText()).contains("Alex declined your request for “Catan”. You can ask again later");
	}

	@Test
	void theEmailsAreInTheRecipientsLanguage() throws Exception {
		change(alex, a -> a.changeLanguage(LanguageResolver.CZECH));
		change(sam, s -> s.changeLanguage(LanguageResolver.CZECH));
		ask("Na páteční hru");

		Sent request = onlyMail();
		assertThat(request.getSubject()).isEqualTo("Žádost o půjčení: „Catan“ (Sam)");
		assertThat(request.getText()).startsWith("Dobrý den, Alex,")
			.contains("Sam si od vás chce půjčit „Catan“.\n\nZpráva: „Na páteční hru“\n\n"
					+ "Půjčit, nebo odmítnout to můžete v sekci Půjčky.")
			.contains("e-maily o žádostech o půjčení a odpovědích na ně");

		LocalDate due = dates.today().plusDays(7);
		answer("approve", due);
		Sent approved = onlyMail();
		assertThat(approved.getSubject()).isEqualTo("Žádost schválena: „Catan“ (Alex)");
		assertThat(approved.getText())
			.contains("Můžete si půjčit „Catan“ do " + dates.format(due, LanguageResolver.CZECH) + ". Půjčuje Alex");
	}

	@Test
	void withdrawingARequestSendsNothing() throws Exception {
		ask("");
		onlyMail();

		mvc.perform(post("/loans/{id}/cancel", onlyLoan().getId()).with(as(sam)).with(csrf()))
			.andExpect(flash().attributeExists("message"));

		noMail();
	}

	@Test
	void aRefusedRequestSendsNothing() throws Exception {
		ask("");
		onlyMail();

		ask("").andExpect(flash().attribute("error", "You've already asked for this."));

		noMail();
	}

	@Test
	void nobodyIsEmailedWhoTurnedTheseOffOrHasntConfirmedTheirAddress() throws Exception {
		change(alex, a -> a.turnOff(EmailList.REQUESTS));
		// Kim's address was never confirmed: a typo at sign-up mustn't email a stranger.
		AppUser kim = users.save(new AppUser("kim@example.com", "Kim", "{noop}x"));
		groupService.join(flat.getInviteToken(), kim.getId());

		ask(kim, "");
		noMail();
		answer("decline", null);
		noMail();
	}

	@Test
	void turningTheseOffLeavesTheOtherEmailsOn() {
		change(sam, s -> s.turnOff(EmailList.REQUESTS));

		AppUser saved = users.findById(sam.getId()).orElseThrow();
		assertThat(saved.wants(EmailList.REQUESTS)).isFalse();
		assertThat(saved.wants(EmailList.BORROWED)).isTrue();
		assertThat(saved.wants(EmailList.LENT)).isTrue();
	}

	@Test
	void aMailServerThatIsDownDoesntUndoTheRequest() throws Exception {
		doThrow(new MailSendException("connection refused")).when(mailSender).send(any(MimeMessage.class));

		ask("").andExpect(flash().attribute("message", "Asked Alex for “Catan”. You'll see the answer under Loans."));

		assertThat(onlyLoan().getStatus()).isEqualTo(LoanStatus.REQUESTED);
	}
}
