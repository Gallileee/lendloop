package io.github.gallileee.lendloop.group;

import io.github.gallileee.lendloop.group.GroupMember.Role;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.ItemRepository;
import io.github.gallileee.lendloop.user.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Groups and membership. Only members can see a group; only its owner manages the invite link.
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

	// Everything the group page shows, loaded in one transaction.
	public record GroupPage(AppGroup group, GroupMember me, List<GroupMember> members, List<Item> lendableItems) {
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
		GroupMember me = membership(groupId, userId);
		return new GroupPage(me.getGroup(), me, members.findByGroupIdWithUser(groupId),
				items.findLendableInGroup(groupId));
	}

	@Transactional
	public AppGroup replaceInvite(Long groupId, Long userId) {
		GroupMember me = membership(groupId, userId);
		if (!me.isOwner()) {
			throw new AccessDeniedException("Only the group owner can replace the invite link");
		}
		AppGroup group = me.getGroup();
		group.replaceInvite(tokens.next(), clock.instant().plus(INVITE_VALIDITY));
		return group;
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

	private GroupMember membership(Long groupId, Long userId) {
		return members.findByGroupIdAndUserId(groupId, userId).orElseThrow(() -> new GroupNotFoundException(groupId));
	}

	public static class OwnerCannotLeaveException extends RuntimeException {

		OwnerCannotLeaveException() {
			super("The owner can't leave their own group");
		}
	}
}
