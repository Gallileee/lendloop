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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.gallileee.lendloop.WebTest;
import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.ItemRepository;
import io.github.gallileee.lendloop.loan.LoanRepository;
import io.github.gallileee.lendloop.loan.LoanService;
import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Rename, remove a member, hand over and delete: things only the owner may do.
class GroupManagementTests extends WebTest {

	@Autowired
	AppUserRepository users;

	@Autowired
	AppGroupRepository groups;

	@Autowired
	GroupMemberRepository members;

	@Autowired
	ItemRepository items;

	@Autowired
	LoanRepository loans;

	@Autowired
	LoanService loanService;

	@Autowired
	GroupService groupService;

	AppUser alex;
	AppUser sam;
	AppUser kim;
	AppGroup group;

	@BeforeEach
	void createFlatWithAlexOwningAndSamIn() {
		alex = users.save(new AppUser("alex@example.com", "Alex", "{noop}irrelevant"));
		sam = users.save(new AppUser("sam@example.com", "Sam", "{noop}irrelevant"));
		kim = users.save(new AppUser("kim@example.com", "Kim", "{noop}irrelevant"));
		GroupForm form = new GroupForm();
		form.setName("Flat 4B");
		group = groupService.create(alex.getId(), form);
		groupService.join(group.getInviteToken(), sam.getId());
	}

	private static RequestPostProcessor as(AppUser user) {
		return user(AppUserDetails.from(user));
	}

	private GroupMember membershipOf(AppUser user) {
		return members.findByGroupIdAndUserId(group.getId(), user.getId()).orElseThrow();
	}

	private String currentToken() {
		return groups.findById(group.getId()).orElseThrow().getInviteToken();
	}

	@Test
	void theOwnerSeesTheSettingsAndMemberControlsButAMemberDoesNot() throws Exception {
		mvc.perform(get("/groups/{id}", group.getId()).with(as(alex)))
			.andExpect(content().string(containsString("Group settings")))
			.andExpect(content().string(containsString("Make owner")))
			.andExpect(content().string(containsString("/groups/" + group.getId() + "/members/" + sam.getId() + "/remove")))
			// No controls next to the owner's own name.
			.andExpect(content().string(not(containsString("/members/" + alex.getId() + "/remove"))));
		mvc.perform(get("/groups/{id}", group.getId()).with(as(sam)))
			.andExpect(content().string(not(containsString("Group settings"))))
			.andExpect(content().string(not(containsString("Make owner"))));
	}

	@Test
	void theOwnerCanRenameTheGroup() throws Exception {
		mvc.perform(post("/groups/{id}/rename", group.getId()).with(as(alex)).with(csrf()).param("name", "  Flat 5C "))
			.andExpect(redirectedUrl("/groups/" + group.getId()))
			.andExpect(flash().attribute("message", "Group renamed to Flat 5C."));
		assertThat(groups.findById(group.getId()).orElseThrow().getName()).isEqualTo("Flat 5C");
	}

	@Test
	void aRenameIsValidatedLikeANewGroup() throws Exception {
		mvc.perform(post("/groups/{id}/rename", group.getId()).with(as(alex)).with(csrf()).param("name", " "))
			.andExpect(redirectedUrl("/groups/" + group.getId() + "#settings"))
			.andExpect(flash().attribute("error", "Give the group a name."));
		mvc.perform(post("/groups/{id}/rename", group.getId()).with(as(alex)).with(csrf()).param("name", "x".repeat(61)))
			.andExpect(flash().attribute("error", "Use at most 60 characters."));
		assertThat(groups.findById(group.getId()).orElseThrow().getName()).isEqualTo("Flat 4B");
	}

	@Test
	void membersAndOutsidersCannotManageTheGroup() throws Exception {
		String[][] requests = {
				{"/groups/{id}/rename", "name", "Mine now"},
				{"/groups/{id}/owner", "userId", String.valueOf(sam.getId())},
				{"/groups/{id}/delete", "confirmName", "Flat 4B"}};
		for (String[] r : requests) {
			mvc.perform(post(r[0], group.getId()).with(as(sam)).with(csrf()).param(r[1], r[2]))
				.andExpect(status().isForbidden());
			// Someone outside the group doesn't even learn that it exists.
			mvc.perform(post(r[0], group.getId()).with(as(kim)).with(csrf()).param(r[1], r[2]))
				.andExpect(status().isNotFound());
		}
		mvc.perform(post("/groups/{id}/members/{u}/remove", group.getId(), alex.getId()).with(as(sam)).with(csrf()))
			.andExpect(status().isForbidden());

		assertThat(groups.findById(group.getId())).get().extracting(AppGroup::getName).isEqualTo("Flat 4B");
		assertThat(membershipOf(alex).isOwner()).isTrue();
		assertThat(membershipOf(sam).isOwner()).isFalse();
	}

	@Test
	void removingAMemberEndsTheirAccessAndReplacesTheInviteLink() throws Exception {
		String oldToken = currentToken();

		mvc.perform(post("/groups/{id}/members/{u}/remove", group.getId(), sam.getId()).with(as(alex)).with(csrf()))
			.andExpect(redirectedUrl("/groups/" + group.getId()))
			.andExpect(flash().attribute("message",
					"Sam is no longer in the group. The invite link was replaced, so they can't rejoin with it."));

		assertThat(groupService.isMember(group.getId(), sam.getId())).isFalse();
		mvc.perform(get("/groups/{id}", group.getId()).with(as(sam))).andExpect(status().isNotFound());
		assertThat(currentToken()).isNotEqualTo(oldToken);
		mvc.perform(post("/join/{token}", oldToken).with(as(sam)).with(csrf())).andExpect(status().isNotFound());
		assertThat(groupService.isMember(group.getId(), sam.getId())).isFalse();
	}

	@Test
	void anAgreedLoanOutlivesTheRemoval() throws Exception {
		Item drill = items.save(new Item(alex, "Cordless drill", null, Category.TOOL, true));
		var loan = loanService.request(drill.getId(), sam.getId(), null);
		loanService.approve(loan.getId(), alex.getId(), java.time.LocalDate.now().plusDays(7));

		groupService.removeMember(group.getId(), alex.getId(), sam.getId());

		assertThat(loans.findById(loan.getId())).get().extracting(l -> l.getStatus().name()).isEqualTo("ACTIVE");
		mvc.perform(get("/loans").with(as(sam))).andExpect(content().string(containsString("Cordless drill")));
	}

	@Test
	void theOwnerCannotRemoveThemselvesOrSomeoneNotInTheGroup() throws Exception {
		mvc.perform(post("/groups/{id}/members/{u}/remove", group.getId(), alex.getId()).with(as(alex)).with(csrf()))
			.andExpect(redirectedUrl("/groups/" + group.getId()))
			.andExpect(flash().attribute("error", "You can't remove yourself. Hand the group over first, or delete it."));
		mvc.perform(post("/groups/{id}/members/{u}/remove", group.getId(), kim.getId()).with(as(alex)).with(csrf()))
			.andExpect(status().isNotFound());
		assertThat(members.countByGroupId(group.getId())).isEqualTo(2);
	}

	@Test
	void handingOverSwapsTheRolesAndTheOldOwnerMayThenLeave() throws Exception {
		mvc.perform(post("/groups/{id}/owner", group.getId()).with(as(alex)).with(csrf())
				.param("userId", String.valueOf(sam.getId())))
			.andExpect(redirectedUrl("/groups/" + group.getId()))
			.andExpect(flash().attribute("message", "Sam owns the group now. You're still a member."));

		assertThat(membershipOf(sam).isOwner()).isTrue();
		assertThat(membershipOf(alex).isOwner()).isFalse();
		// The new owner manages the invite link; the old one no longer can.
		mvc.perform(post("/groups/{id}/invite", group.getId()).with(as(alex)).with(csrf()))
			.andExpect(status().isForbidden());
		mvc.perform(get("/groups/{id}", group.getId()).with(as(sam)))
			.andExpect(content().string(containsString(currentToken())));

		mvc.perform(post("/groups/{id}/leave", group.getId()).with(as(alex)).with(csrf()))
			.andExpect(redirectedUrl("/groups"));
		assertThat(groupService.isMember(group.getId(), alex.getId())).isFalse();
	}

	@Test
	void theGroupCanOnlyBeHandedToAnotherMember() throws Exception {
		mvc.perform(post("/groups/{id}/owner", group.getId()).with(as(alex)).with(csrf())
				.param("userId", String.valueOf(alex.getId())))
			.andExpect(flash().attribute("error", "You already own this group."));
		mvc.perform(post("/groups/{id}/owner", group.getId()).with(as(alex)).with(csrf())
				.param("userId", String.valueOf(kim.getId())))
			.andExpect(status().isNotFound());
		assertThat(membershipOf(alex).isOwner()).isTrue();
		assertThat(members.findByGroupIdAndUserId(group.getId(), kim.getId())).isEmpty();
	}

	@Test
	void deletingNeedsTheExactNameTypedAgain() throws Exception {
		for (String typed : new String[] {"", "flat 4b", "Flat"}) {
			mvc.perform(post("/groups/{id}/delete", group.getId()).with(as(alex)).with(csrf()).param("confirmName", typed))
				.andExpect(redirectedUrl("/groups/" + group.getId() + "#settings"))
				.andExpect(flash().attribute("error", "Type the group's name exactly to delete it."));
		}
		mvc.perform(post("/groups/{id}/delete", group.getId()).with(as(alex)).with(csrf()))
			.andExpect(flash().attribute("error", "Type the group's name exactly to delete it."));
		assertThat(groups.existsById(group.getId())).isTrue();
	}

	@Test
	void deletingRemovesTheGroupForEveryoneButKeepsTheirThings() throws Exception {
		Item catan = items.save(new Item(sam, "Catan", null, Category.GAME, true));
		String token = currentToken();

		mvc.perform(post("/groups/{id}/delete", group.getId()).with(as(alex)).with(csrf()).param("confirmName", " Flat 4B "))
			.andExpect(redirectedUrl("/groups"))
			.andExpect(flash().attribute("message", "Flat 4B was deleted. Everyone's things and loans are kept."));

		assertThat(groups.existsById(group.getId())).isFalse();
		assertThat(members.countByGroupId(group.getId())).isZero();
		assertThat(items.existsById(catan.getId())).isTrue();
		mvc.perform(get("/groups/{id}", group.getId()).with(as(sam))).andExpect(status().isNotFound());
		mvc.perform(get("/join/{token}", token).with(as(kim))).andExpect(status().isNotFound());
		mvc.perform(get("/groups").with(as(sam)))
			.andExpect(content().string(not(containsString("href=\"/groups/" + group.getId() + "\""))));
	}
}
