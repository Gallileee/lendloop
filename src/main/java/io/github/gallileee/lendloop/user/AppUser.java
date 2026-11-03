package io.github.gallileee.lendloop.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	// Emails about things I borrowed: due tomorrow, overdue.
	@Column(name = "remind_borrowed", nullable = false)
	private boolean remindBorrowed = true;

	// An email when something I lent out becomes overdue.
	@Column(name = "notify_lent_overdue", nullable = false)
	private boolean notifyLentOverdue = true;

	protected AppUser() {
		// for JPA
	}

	public AppUser(String email, String displayName, String passwordHash) {
		this.email = email;
		this.displayName = displayName;
		this.passwordHash = passwordHash;
		this.createdAt = Instant.now();
	}

	public void changeSettings(String displayName, boolean remindBorrowed, boolean notifyLentOverdue) {
		this.displayName = displayName;
		this.remindBorrowed = remindBorrowed;
		this.notifyLentOverdue = notifyLentOverdue;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public boolean isRemindBorrowed() {
		return remindBorrowed;
	}

	public boolean isNotifyLentOverdue() {
		return notifyLentOverdue;
	}
}
