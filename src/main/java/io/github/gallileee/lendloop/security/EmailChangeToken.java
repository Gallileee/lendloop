package io.github.gallileee.lendloop.security;

import io.github.gallileee.lendloop.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Instant;

// A pending email change: the new address and the hash of the link sent to it.
@Entity
public class EmailChangeToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AppUser user;

	@Column(name = "new_email", nullable = false, length = 254)
	private String newEmail;

	@Column(name = "token_hash", nullable = false)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected EmailChangeToken() {
		// for JPA
	}

	EmailChangeToken(AppUser user, String newEmail, String tokenHash, Instant expiresAt, Instant createdAt) {
		this.user = user;
		this.newEmail = newEmail;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
		this.createdAt = createdAt;
	}

	public AppUser getUser() {
		return user;
	}

	public String getNewEmail() {
		return newEmail;
	}

	public boolean isValidAt(Instant now) {
		return now.isBefore(expiresAt);
	}
}
