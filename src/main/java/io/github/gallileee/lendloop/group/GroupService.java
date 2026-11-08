package io.github.gallileee.lendloop.group;

import io.github.gallileee.lendloop.group.GroupMember.Role;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.ItemRepository;
import io.github.gallileee.lendloop.user.AppUserRepository;
import io.github.gallileee.lendloop.web.RuleException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Groups and membership. Only members can see a group; only its owner manages it (invite link, name,
// members, handing over, deleting). A non-member gets a 404, a member who isn't the owner a 403.
@Service
public class GroupService {

	// Long enough to share in a chat and for friends to get to it; short enough that an
	// old link found later is useless. The owner can make a new one at any time.
	static final Duration INVITE_VALIDITY = Duration.ofDays(7);

	private final AppGroupRepository groups;
	private final GroupMemberRepository members;
	private final ItemRepository items;
	private final AppUserRepository users;
	private final InviteTokens tokens;
	private final Clock clock;

	public GroupService(AppGroupRepository groups, GroupMemberRepository members, ItemRepository items,
			AppUserRepository users, InviteTokens tokens, Clock clock) {
		this.groups = groups;
		this.members = members;
		this.items = items;
		this.users = users;
		this.tokens = tokens;
		this.clock = clock;
	}

	// What a group can borrow, in name order (ignoring case); the id keeps equal names stable.
	private static final Sort ITEM_ORDER = Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id"));

	// Everything the group page shows, loaded in one transaction. `items` are the lendable items
	// that match the filter; `lendableCount` counts them all, so the page can say "2 of 9" and
	// tell "nothing matches" apart from "nobody has added anything yet".
	public record GroupPage(AppGroup group, GroupMember me, List<GroupMember> members, ItemFilter filter,
			List<Item> items, long lendableCount) {
	}

	@Transactional
	public AppGroup create(Long userId, GroupForm form) {
		Instant now = clock.instant();
		AppGroup group = groups.save(new AppGroup(form.getName().trim(), tokens.next(), now.plus(INVITE_VALIDITY), now));
		members.save(new GroupMember(group, users.getReferenceById(userId), Role.OWNER, now));
		return group;
	}

	@Transactional(readOnly = true)
	public List<GroupMember> membershipsOf(Long userId) {
		return members.findByUserIdWithGroup(userId);
	}

	@Transactional(readOnly = true)
	public GroupPage page(Long groupId, Long userId) {
		return page(groupId, userId, ItemFilter.NONE);
	}

	@Transactional(readOnly = true)
	public GroupPage page(Long groupId, Long userId, ItemFilter filter) {
		GroupMember me = membership(groupId, userId);
		Specification<Item> lendable = GroupItemSpecs.lendableInGroup(groupId);
		List<Item> shown = items.findAll(
				lendable.and(GroupItemSpecs.matching(filter)).and(GroupItemSpecs.withOwner()), ITEM_ORDER);
		long lendableCount = filter.isActive() ? items.count(lendable) : shown.size();
		return new GroupPage(me.getGroup(), me, members.findByGroupIdWithUser(groupId), filter, shown,
				lendableCount);
	}

	@Transactional
	public AppGroup replaceInvite(Long groupId, Long userId) {
		AppGroup group = ownership(groupId, userId).getGroup();
		newInvite(group);
		return group;
	}

	@Transactional
	public AppGroup rename(Long groupId, Long userId, GroupForm form) {
		AppGroup group = ownership(groupId, userId).getGroup();
		group.rename(form.getName().trim());
		return group;
	}

	// Removes someone else from the group and returns their name for the confirmation message.
	// The invite link is replaced too: the removed member knows it and could otherwise just rejoin.
	// Loans already agreed are left alone: the thing still has to come back.
	@Transactional
	public String removeMember(Long groupId, Long ownerId, Long memberUserId) {
		GroupMember owner = ownership(groupId, ownerId);
		if (ownerId.equals(memberUserId)) {
			throw new GroupRuleException("group.rule.removeSelf");
		}
		GroupMember member = otherMember(groupId, memberUserId);
		String name = member.getUser().getDisplayName();
		members.delete(member);
		newInvite(owner.getGroup());
		return name;
	}

	// Makes another member the owner; the current owner stays in the group as a member (and may
	// then leave). Both rows change in one transaction, so there is never a moment with two
	// owners or none.
	@Transactional
	public String handOver(Long groupId, Long ownerId, Long newOwnerUserId) {
		GroupMember owner = ownership(groupId, ownerId);
		if (ownerId.equals(newOwnerUserId)) {
			throw new GroupRuleException("group.rule.alreadyOwner");
		}
		GroupMember next = otherMember(groupId, newOwnerUserId);
		owner.becomeMember();
		next.becomeOwner();
		return next.getUser().getDisplayName();
	}

	// Deleting needs the group's name typed again, like GitHub does for repositories: a button alone
	// is too easy to press by mistake, and this can't be undone. Items and loans belong to people,
	// not groups, so they stay; only the group and its memberships go.
	@Transactional
	public String delete(Long groupId, Long ownerId, String typedName) {
		AppGroup group = ownership(groupId, ownerId).getGroup();
		if (typedName == null || !typedName.trim().equals(group.getName())) {
			throw new GroupRuleException("group.rule.confirmName");
		}
		String name = group.getName();
		members.deleteByGroupId(groupId);
		groups.deleteById(groupId);
		return name;
	}

	// The group an invite link leads to, or empty when the token is unknown or expired.
	@Transactional(readOnly = true)
	public Optional<AppGroup> findByValidInvite(String token) {
		return groups.findByInviteToken(token).filter(g -> g.isInviteValidAt(clock.instant()));
	}

	@Transactional(readOnly = true)
	public boolean isMember(Long groupId, Long userId) {
		return members.existsByGroupIdAndUserId(groupId, userId);
	}

	// Following the link again keeps the one membership. Two requests at the same moment can both
	// pass the check; the unique constraint then rejects the second, and the caller treats that
	// DataIntegrityViolationException as "already a member" (it can't be caught in here, because
	// the failed insert has already marked this transaction for rollback).
	@Transactional
	public Optional<AppGroup> join(String token, Long userId) {
		Optional<AppGroup> group = findByValidInvite(token);
		group.filter(g -> !members.existsByGroupIdAndUserId(g.getId(), userId))
			.ifPresent(g -> members.saveAndFlush(
					new GroupMember(g, users.getReferenceById(userId), Role.MEMBER, clock.instant())));
		return group;
	}

	// The owner can't leave, so a group is never left without someone who manages the invite link.
	@Transactional
	public void leave(Long groupId, Long userId) {
		GroupMember me = membership(groupId, userId);
		if (me.isOwner()) {
			throw new OwnerCannotLeaveException();
		}
		members.delete(me);
	}

	private void newInvite(AppGroup group) {
		group.replaceInvite(tokens.next(), clock.instant().plus(INVITE_VALIDITY));
	}

	private GroupMember ownership(Long groupId, Long userId) {
		GroupMember me = membership(groupId, userId);
		if (!me.isOwner()) {
			throw new AccessDeniedException("Only the group owner can do this");
		}
		return me;
	}

	// Another member of the group (with their user loaded for the name), or 404.
	private GroupMember otherMember(Long groupId, Long userId) {
		return members.findByGroupIdAndUserIdWithUser(groupId, userId)
			.orElseThrow(() -> new MemberNotFoundException(userId));
	}

	private GroupMember membership(Long groupId, Long userId) {
		return members.findByGroupIdAndUserId(groupId, userId).orElseThrow(() -> new GroupNotFoundException(groupId));
	}

	public static class OwnerCannotLeaveException extends RuntimeException {

		OwnerCannotLeaveException() {
			super("The owner can't leave their own group");
		}
	}

	// A request the owner may make, but not in this state; shown to them as a flash error.
	public static class GroupRuleException extends RuleException {

		GroupRuleException(String code) {
			super(code);
		}
	}
}
