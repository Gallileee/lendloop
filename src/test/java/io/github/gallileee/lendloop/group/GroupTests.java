package io.github.gallileee.lendloop.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.gallileee.lendloop.WebTest;
import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.ItemRepository;
import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class GroupTests extends WebTest {

	@Autowired
	AppUserRepository users;

	@Autowired
	AppGroupRepository groups;

	@Autowired
	GroupMemberRepository members;

	@Autowired
	ItemRepository items;

	@Autowired
	GroupService groupService;

	@Autowired
	PasswordEncoder passwordEncoder;

	AppUser alex;
	AppUser sam;
	AppUser kim;

	@BeforeEach
	void createUsers() {
		alex = users.save(new AppUser("alex@example.com", "Alex", passwordEncoder.encode("alex password 123")));
		sam = users.save(new AppUser("sam@example.com", "Sam", "{noop}irrelevant"));
		kim = users.save(new AppUser("kim@example.com", "Kim", "{noop}irrelevant"));
	}

	private static RequestPostProcessor as(AppUser user) {
		return user(AppUserDetails.from(user));
	}

	// Alex's group with Sam in it.
	private AppGroup flatWithAlexAndSam() {
		GroupForm form = new GroupForm();
		form.setName("Flat 4B");
		AppGroup group = groupService.create(alex.getId(), form);
		groupService.join(group.getInviteToken(), sam.getId());
		return group;
	}

	@Test
	void creatingAGroupMakesMeItsOwnerAndShowsTheInviteLink() throws Exception {
		mvc.perform(post("/groups").with(as(alex)).with(csrf()).param("name", "  Flat 4B "))
			.andExpect(status().is3xxRedirection());

		AppGroup group = groups.findAll().get(0);
		assertThat(group.getName()).isEqualTo("Flat 4B");
		assertThat(group.getInviteToken()).matches("[A-Za-z0-9_-]{43}");
		assertThat(members.findByGroupIdAndUserId(group.getId(), alex.getId())).get()
			.extracting(GroupMember::isOwner).isEqualTo(true);

		mvc.perform(get("/groups/{id}", group.getId()).with(as(alex)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("/join/" + group.getInviteToken())))
			.andExpect(content().string(containsString("Make a new link")));
		mvc.perform(get("/groups").with(as(alex)))
			.andExpect(content().string(containsString("Flat 4B")));
	}

	@Test
	void aGroupNeedsAName() throws Exception {
		mvc.perform(post("/groups").with(as(alex)).with(csrf()).param("name", " "))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Give the group a name.")));
		assertThat(groups.count()).isZero();
	}

	@Test
	void theInviteLinkLetsAFriendJoinAfterConfirming() throws Exception {
		GroupForm form = new GroupForm();
		form.setName("Climbing club");
		AppGroup group = groupService.create(alex.getId(), form);

		// Opening the link (or a chat app's link preview fetching it) doesn't join yet.
		mvc.perform(get("/join/{token}", group.getInviteToken()).with(as(sam)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Join <span>Climbing club</span>?")));
		assertThat(groupService.isMember(group.getId(), sam.getId())).isFalse();

		mvc.perform(post("/join/{token}", group.getInviteToken()).with(as(sam)).with(csrf()))
			.andExpect(redirectedUrl("/groups/" + group.getId()))
			.andExpect(flash().attribute("message", "Welcome to Climbing club."));
		assertThat(groupService.isMember(group.getId(), sam.getId())).isTrue();

		// Following the link again just opens the group; there is still one membership.
		mvc.perform(post("/join/{token}", group.getInviteToken()).with(as(sam)).with(csrf()));
		mvc.perform(get("/join/{token}", group.getInviteToken()).with(as(sam)))
			.andExpect(redirectedUrl("/groups/" + group.getId()));
		assertThat(members.countByGroupId(group.getId())).isEqualTo(2);
	}

	@Test
	void unknownAndExpiredInvitesAreRejected() throws Exception {
		AppGroup group = flatWithAlexAndSam();
		group.replaceInvite("expired-token", Instant.now().minusSeconds(1));
		groups.saveAndFlush(group);

		for (String token : new String[] {"expired-token", "no-such-token"}) {
			mvc.perform(get("/join/{token}", token).with(as(kim)))
				.andExpect(status().isNotFound())
				.andExpect(content().string(containsString("This invite link doesn't work")));
			mvc.perform(post("/join/{token}", token).with(as(kim)).with(csrf()))
				.andExpect(status().isNotFound());
		}
		assertThat(groupService.isMember(group.getId(), kim.getId())).isFalse();
	}

	@Test
	void aNewInviteLinkReplacesTheOldOne() throws Exception {
		AppGroup group = flatWithAlexAndSam();
		String oldToken = group.getInviteToken();

		mvc.perform(post("/groups/{id}/invite", group.getId()).with(as(alex)).with(csrf()))
			.andExpect(redirectedUrl("/groups/" + group.getId()));

		String newToken = groups.findById(group.getId()).orElseThrow().getInviteToken();
		assertThat(newToken).isNotEqualTo(oldToken);
		mvc.perform(get("/join/{token}", oldToken).with(as(kim))).andExpect(status().isNotFound());
		mvc.perform(get("/join/{token}", newToken).with(as(kim))).andExpect(status().isOk());
	}

	@Test
	void onlyTheOwnerSeesAndManagesTheInviteLink() throws Exception {
		AppGroup group = flatWithAlexAndSam();

		mvc.perform(get("/groups/{id}", group.getId()).with(as(sam)))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(group.getInviteToken()))));
		mvc.perform(post("/groups/{id}/invite", group.getId()).with(as(sam)).with(csrf()))
			.andExpect(status().isForbidden());
	}

	@Test
	void outsidersCannotSeeOrTouchAGroup() throws Exception {
		AppGroup group = flatWithAlexAndSam();

		mvc.perform(get("/groups/{id}", group.getId()).with(as(kim))).andExpect(status().isNotFound());
		mvc.perform(post("/groups/{id}/invite", group.getId()).with(as(kim)).with(csrf()))
			.andExpect(status().isNotFound());
		mvc.perform(post("/groups/{id}/leave", group.getId()).with(as(kim)).with(csrf()))
			.andExpect(status().isNotFound());
		mvc.perform(get("/groups").with(as(kim)))
			.andExpect(content().string(not(containsString("href=\"/groups/" + group.getId() + "\""))));
	}

	@Test
	void theGroupShowsWhatMembersLendAndNothingElse() throws Exception {
		AppGroup group = flatWithAlexAndSam();
		items.save(new Item(alex, "Catan", null, Category.GAME, true));
		items.save(new Item(sam, "Cordless drill", null, Category.TOOL, true));
		items.save(new Item(sam, "Private diary", null, Category.OTHER, false));
		items.save(new Item(kim, "Outsider tent", null, Category.OUTDOOR, true));

		mvc.perform(get("/groups/{id}", group.getId()).with(as(alex)))
			.andExpect(content().string(containsString("Catan")))
			.andExpect(content().string(containsString("Yours")))
			.andExpect(content().string(containsString("Cordless drill")))
			.andExpect(content().string(containsString("from Sam")))
			.andExpect(content().string(not(containsString("Private diary"))))
			.andExpect(content().string(not(containsString("Outsider tent"))));
	}

	@Test
	void aMemberCanLeaveButTheOwnerCannot() throws Exception {
		AppGroup group = flatWithAlexAndSam();

		mvc.perform(post("/groups/{id}/leave", group.getId()).with(as(alex)).with(csrf()))
			.andExpect(redirectedUrl("/groups/" + group.getId()))
			.andExpect(flash().attribute("error", "You created this group, so you can't leave it."));
		mvc.perform(post("/groups/{id}/leave", group.getId()).with(as(sam)).with(csrf()))
			.andExpect(redirectedUrl("/groups"));

		assertThat(groupService.isMember(group.getId(), alex.getId())).isTrue();
		assertThat(groupService.isMember(group.getId(), sam.getId())).isFalse();
		mvc.perform(get("/groups/{id}", group.getId()).with(as(sam))).andExpect(status().isNotFound());
	}

	@Test
	void someoneLoggedOutIsBroughtBackToTheInviteAfterLoggingIn() throws Exception {
		GroupForm form = new GroupForm();
		form.setName("Book club");
		AppGroup group = groupService.create(sam.getId(), form);
		MockHttpSession session = new MockHttpSession();

		mvc.perform(get("/join/{token}", group.getInviteToken()).session(session))
			.andExpect(redirectedUrl("/login"));
		mvc.perform(post("/login").session(session).with(csrf())
				.param("email", "alex@example.com")
				.param("password", "alex password 123"))
			.andExpect(header().string("Location", containsString("/join/" + group.getInviteToken())));
	}

	@Test
	void pagesDontLeakInviteTokensToOtherSites() throws Exception {
		mvc.perform(get("/groups").with(as(alex)))
			.andExpect(header().string("Referrer-Policy", "same-origin"));
	}
}
