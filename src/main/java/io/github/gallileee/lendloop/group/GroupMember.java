package io.github.gallileee.lendloop.group;

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

@Entity
public class GroupMember {

	public enum Role {
		// Exactly one per group: manages the group and the invite link, and can't leave
		// (hand the group over first, or delete it).
		OWNER,
		MEMBER
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "group_id", nullable = false)
	private AppGroup group;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AppUser user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt;

	protected GroupMember() {
		// for JPA
	}

	public GroupMember(AppGroup group, AppUser user, Role role, Instant joinedAt) {
		this.group = group;
		this.user = user;
		this.role = role;
		this.joinedAt = joinedAt;
	}

	// Handing a group over swaps the roles of two members in one transaction.
	void becomeOwner() {
		this.role = Role.OWNER;
	}

	void becomeMember() {
		this.role = Role.MEMBER;
	}

	public boolean isOwner() {
		return role == Role.OWNER;
	}

	public Long getId() {
		return id;
	}

	public AppGroup getGroup() {
		return group;
	}

	public AppUser getUser() {
		return user;
	}

	public Role getRole() {
		return role;
	}

	public Instant getJoinedAt() {
		return joinedAt;
	}
}
