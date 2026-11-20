package io.github.gallileee.lendloop.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import io.github.gallileee.lendloop.WebTest;
import io.github.gallileee.lendloop.group.AppGroup;
import io.github.gallileee.lendloop.group.AppGroupRepository;
import io.github.gallileee.lendloop.group.GroupMember;
import io.github.gallileee.lendloop.group.GroupMemberRepository;
import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.ItemRepository;
import io.github.gallileee.lendloop.loan.Loan;
import io.github.gallileee.lendloop.loan.LoanRepository;
import io.github.gallileee.lendloop.mail.Mailer;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import io.github.gallileee.lendloop.web.LanguageResolver;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

// UnconfirmedAccountCleanup with the default settings (warned after 23 days, deleted after 30
// and at least 7 days after the warning), run with explicit times instead of the clock.
class UnconfirmedAccountCleanupTests extends WebTest {

	private static final Instant SIGNED_UP = Instant.parse("2026-10-01T10:00:00Z");
	private static final String SUBJECT = "Confirm your email or your Lendloop account will be deleted";
	private static final Pattern LINK = Pattern.compile("https://lendloop\\.test/verify-email\\?token=([A-Za-z0-9_-]{43})\n");

	@MockitoBean
	Mailer mailer;

	@Autowired
	UnconfirmedAccountCleanup cleanup;

	@Autowired
	AppUserRepository users;

	@Autowired
	ItemRepository items;

	@Autowired
	AppGroupRepository groups;

	@Autowired
	GroupMemberRepository members;

	@Autowired
	LoanRepository loans;

	private static Instant day(int days) {
		return SIGNED_UP.plus(Duration.ofDays(days));
	}

	private AppUser signedUp(String email, boolean confirmed) {
		AppUser user = new AppUser(email, "Kim", "{noop}secret");
		if (confirmed) {
			user.markEmailVerified(SIGNED_UP);
		}
		user = users.save(user);
		jdbc.update("update app_user set created_at = ? where id = ?", Timestamp.from(SIGNED_UP), user.getId());
		return user;
	}

	private boolean exists(AppUser user) {
		return users.existsById(user.getId());
	}

	private Instant warnedAt(AppUser user) {
		return users.findById(user.getId()).orElseThrow().getDeletionWarnedAt();
	}

	// The text of the only warning sent since the last call.
	private String warningTo(String email) {
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mailer).send(eq(email), anyString(), text.capture(), isNull());
		clearInvocations(mailer);
		return text.getValue();
	}

	@Test
	void anUnusedUnconfirmedAccountIsWarnedAWeekBeforeAndThenDeleted() throws Exception {
		AppUser kim = signedUp("kim@example.com", false);

		assertThat(cleanup.run(day(22))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 0));
		verify(mailer, never()).send(anyString(), anyString(), anyString(), any());

		assertThat(cleanup.run(day(23))).isEqualTo(new UnconfirmedAccountCleanup.Result(1, 0));
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mailer).send(eq("kim@example.com"), eq(SUBJECT), text.capture(), isNull());
		clearInvocations(mailer);
		assertThat(text.getValue())
			.startsWith("Hi Kim,")
			.contains("You signed up for Lendloop on 1 Oct 2026")
			.contains("The link works for 48 hours")
			.contains("will be deleted on 31 Oct 2026 or soon after")
			.contains("If you didn't sign up, ignore this email");
		assertThat(warnedAt(kim)).isEqualTo(day(23));

		// Warned once only.
		assertThat(cleanup.run(day(24))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 0));
		verify(mailer, never()).send(anyString(), anyString(), anyString(), any());

		assertThat(cleanup.run(day(30).minusSeconds(1))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 0));
		assertThat(exists(kim)).isTrue();

		assertThat(cleanup.run(day(30))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 1));
		assertThat(exists(kim)).isFalse();
		// Its confirmation link went with it (on delete cascade).
		assertThat(jdbc.queryForObject("select count(*) from email_verification_token", Integer.class)).isZero();
	}

	@Test
	void theLinkInTheWarningConfirmsTheAddressAndKeepsTheAccount() throws Exception {
		AppUser kim = signedUp("kim@example.com", false);
		cleanup.run(day(23));
		Matcher link = LINK.matcher(warningTo("kim@example.com"));
		assertThat(link.find()).isTrue();

		mvc.perform(post("/verify-email").with(csrf()).param("token", link.group(1))).andExpect(redirectedUrl("/login"));

		assertThat(cleanup.run(day(30))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 0));
		assertThat(users.findById(kim.getId()).orElseThrow().isEmailVerified()).isTrue();
		assertThat(warnedAt(kim)).as("warning forgotten once confirmed").isNull();
	}

	@Test
	void anAccountNoticedLateStillGetsTheWholeWeek() {
		AppUser kim = signedUp("kim@example.com", false);

		// The job was off for two months: the account is old enough, but was never warned.
		assertThat(cleanup.run(day(60))).isEqualTo(new UnconfirmedAccountCleanup.Result(1, 0));
		assertThat(warningTo("kim@example.com")).contains("will be deleted on 7 Dec 2026");

		assertThat(cleanup.run(day(66))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 0));
		assertThat(exists(kim)).isTrue();
		assertThat(cleanup.run(day(67))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 1));
		assertThat(exists(kim)).isFalse();
	}

	@Test
	void confirmedAccountsAreNeverTouched() {
		AppUser kim = signedUp("kim@example.com", true);

		assertThat(cleanup.run(day(365))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 0));
		assertThat(cleanup.run(day(400))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 0));
		assertThat(exists(kim)).isTrue();
		verify(mailer, never()).send(anyString(), anyString(), anyString(), any());
	}

	private void keptWhen(Consumer<AppUser> use) {
		AppUser kim = signedUp("kim@example.com", false);
		use.accept(kim);

		assertThat(cleanup.run(day(100))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 0));
		assertThat(cleanup.run(day(200))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 0));
		assertThat(exists(kim)).isTrue();
		verify(mailer, never()).send(anyString(), anyString(), anyString(), any());
	}

	@Test
	void anUnconfirmedAccountWithAnItemIsKept() {
		keptWhen(kim -> items.save(new Item(kim, "Drill", null, Category.TOOL, true)));
	}

	@Test
	void anUnconfirmedAccountInAGroupIsKept() {
		keptWhen(kim -> {
			AppGroup group = groups.save(new AppGroup("Flat", "invite-token", day(7), SIGNED_UP));
			members.save(new GroupMember(group, kim, GroupMember.Role.MEMBER, SIGNED_UP));
		});
	}

	@Test
	void anUnconfirmedAccountThatBorrowedSomethingIsKept() {
		keptWhen(kim -> {
			AppUser alex = signedUp("alex@example.com", true);
			Item drill = items.save(new Item(alex, "Drill", null, Category.TOOL, true));
			loans.save(new Loan(drill, kim, null, SIGNED_UP));
		});
	}

	@Test
	void aWarnedAccountThatGetsUsedIsKeptAndWarnedAgainIfItBecomesUnused() {
		AppUser kim = signedUp("kim@example.com", false);
		cleanup.run(day(23));
		warningTo("kim@example.com");

		Item drill = items.save(new Item(kim, "Drill", null, Category.TOOL, true));
		assertThat(cleanup.run(day(30))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 0));
		assertThat(exists(kim)).isTrue();
		assertThat(warnedAt(kim)).isNull();

		// Deleting the item doesn't make the old warning count: a new one goes out first.
		items.delete(drill);
		assertThat(cleanup.run(day(31))).isEqualTo(new UnconfirmedAccountCleanup.Result(1, 0));
		warningTo("kim@example.com");
		assertThat(cleanup.run(day(37))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 0));
		assertThat(cleanup.run(day(38))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 1));
	}

	@Test
	void aWarningThatCannotBeSentIsTriedAgainAndDelaysTheDeletion() {
		AppUser kim = signedUp("kim@example.com", false);
		doThrow(new MailSendException("SMTP down")).when(mailer).send(anyString(), anyString(), anyString(), any());

		assertThat(cleanup.run(day(23))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 0));
		assertThat(warnedAt(kim)).isNull();
		assertThat(jdbc.queryForObject("select count(*) from email_verification_token", Integer.class)).isZero();
		// Not deleted without a warning, however old.
		assertThat(cleanup.run(day(40))).isEqualTo(new UnconfirmedAccountCleanup.Result(0, 0));
		assertThat(exists(kim)).isTrue();
	}

	@Test
	void theWarningIsInTheUsersLanguage() {
		AppUser kim = signedUp("kim@example.com", false);
		jdbc.update("update app_user set preferred_language = ? where id = ?", LanguageResolver.CZECH.getLanguage(),
				kim.getId());

		cleanup.run(day(23));

		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mailer).send(eq("kim@example.com"), eq("Potvrďte svůj e-mail, jinak bude váš účet v Lendloopu smazán"),
				text.capture(), isNull());
		assertThat(text.getValue())
			.contains("Dne 1. 10. 2026 jste se zaregistrovali")
			.contains("Jinak bude účet smazán 31. 10. 2026 nebo krátce poté");
	}

	@Test
	void theWarningPeriodMustFitBeforeTheDeletion() {
		for (Duration warning : new Duration[] { Duration.ZERO, Duration.ofDays(30), Duration.ofDays(31) }) {
			assertThatThrownBy(() -> new UnconfirmedAccountCleanup(users, null, mailer, null, null,
					Clock.systemUTC(), null, Duration.ofDays(30), warning))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("warning-period");
		}
	}

	// --- the deletion date on the pages ---

	private ResultActions page(String path, AppUser who, String language) throws Exception {
		AppUser fresh = users.findById(who.getId()).orElseThrow();
		return mvc.perform(get(path).with(user(AppUserDetails.from(fresh))).header("Accept-Language", language));
	}

	@Test
	void theBannerShowsTheDeletionDateOnceTheAccountIsWarned() throws Exception {
		AppUser kim = signedUp("kim@example.com", false);

		page("/items", kim, "en")
			.andExpect(content().string(containsString("Confirm your email address to get loan reminders")))
			.andExpect(content().string(not(containsString("will be deleted"))));
		assertThat(cleanup.deletionDateOf(kim.getId())).isEmpty();

		cleanup.run(day(23));
		assertThat(cleanup.deletionDateOf(kim.getId())).contains(LocalDate.of(2026, 10, 31));
		page("/items", kim, "en")
			.andExpect(content().string(containsString("banner banner-warning")))
			.andExpect(content().string(containsString("This account will be deleted on 31 Oct 2026 or soon after")))
			.andExpect(content().string(not(containsString("to get loan reminders"))));
		page("/settings", kim, "en")
			.andExpect(content().string(containsString("it will be deleted on 31 Oct 2026 or soon after")));
		page("/items", kim, "cs")
			.andExpect(content().string(containsString("Tento účet bude 31. 10. 2026 nebo krátce potom smazán")));
		page("/settings", kim, "cs")
			.andExpect(content().string(containsString("bude 31. 10. 2026 nebo krátce potom smazán. Zachráníte")));
	}

	@Test
	void theDateMatchesTheWarningEmailWhenTheJobWasLate() {
		AppUser kim = signedUp("kim@example.com", false);
		cleanup.run(day(60));
		assertThat(warningTo("kim@example.com")).contains("will be deleted on 7 Dec 2026");
		assertThat(cleanup.deletionDateOf(kim.getId())).contains(LocalDate.of(2026, 12, 7));
	}

	@Test
	void theDateGoesAwayAsSoonAsTheAccountIsUsedOrConfirmed() throws Exception {
		AppUser kim = signedUp("kim@example.com", false);
		AppUser alex = signedUp("alex@example.com", false);
		cleanup.run(day(23));

		// An item hides the date at once, before the next run forgets the warning.
		items.save(new Item(kim, "Drill", null, Category.TOOL, true));
		assertThat(warnedAt(kim)).isNotNull();
		assertThat(cleanup.deletionDateOf(kim.getId())).isEmpty();
		page("/items", kim, "en")
			.andExpect(content().string(containsString("Confirm your email address to get loan reminders")))
			.andExpect(content().string(not(containsString("will be deleted"))));

		AppUser confirmed = users.findById(alex.getId()).orElseThrow();
		confirmed.markEmailVerified(day(24));
		users.save(confirmed);
		assertThat(cleanup.deletionDateOf(alex.getId())).isEmpty();
		page("/items", alex, "en")
			.andExpect(content().string(not(containsString("Confirm your email address"))))
			.andExpect(content().string(not(containsString("will be deleted"))));
	}

	@Test
	void confirmedAccountsHaveNoDeletionDate() {
		AppUser kim = signedUp("kim@example.com", true);
		cleanup.run(day(365));
		assertThat(cleanup.deletionDateOf(kim.getId())).isEmpty();
	}
}
