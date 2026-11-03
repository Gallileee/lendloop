package io.github.gallileee.lendloop.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// A group of friends who lend to each other. Membership is in GroupMember.
@Entity
@Table(name = "app_group")
public class AppGroup {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(name = "invite_token", nullable = false, unique = true)
	private String inviteToken;

	@Column(name = "invite_expires_at", nullable = false)
	private Instant inviteExpiresAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AppGroup() {
		// for JPA
	}

	public AppGroup(String name, String inviteToken, Instant inviteExpiresAt, Instant createdAt) {
		this.name = name;
		this.createdAt = createdAt;
		replaceInvite(inviteToken, inviteExpiresAt);
	}

	// The old link stops working at once, e.g. after it was shared somewhere it shouldn't have been.
	public final void replaceInvite(String token, Instant expiresAt) {
		this.inviteToken = token;
		this.inviteExpiresAt = expiresAt;
	}

	public boolean isInviteValidAt(Instant now) {
		return now.isBefore(inviteExpiresAt);
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getInviteToken() {
		return inviteToken;
	}

	public Instant getInviteExpiresAt() {
		return inviteExpiresAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
