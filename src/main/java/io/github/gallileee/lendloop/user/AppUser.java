package io.github.gallileee.lendloop.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import io.github.gallileee.lendloop.web.LanguageResolver;
import java.time.Instant;
import java.util.Locale;

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

	// See V9__session_version.sql: sessions that logged in with an older number are ended.
	@Column(name = "session_version", nullable = false)
	private int sessionVersion;

	// See V10__email_verification.sql. Null until the user follows a link sent to the address.
	@Column(name = "email_verified_at")
	private Instant emailVerifiedAt;

	// See V11__preferred_language.sql: the language of this user's emails, "en" or "cs".
	@Column(name = "preferred_language", nullable = false, length = 8)
	private String preferredLanguage = LanguageResolver.ENGLISH.getLanguage();

	protected AppUser() {
		// for JPA
	}

	public AppUser(String email, String displayName, String passwordHash) {
		this.email = email;
		this.displayName = displayName;
		this.passwordHash = passwordHash;
		this.createdAt = Instant.now();
	}

	// Only a supported language is stored; anything else keeps the current one.
	public void changeLanguage(Locale locale) {
		Locale supported = locale == null ? null : LanguageResolver.supported(locale.getLanguage());
		if (supported != null) {
			this.preferredLanguage = supported.getLanguage();
		}
	}

	// The language to write this user's emails in.
	public Locale getLocale() {
		Locale supported = LanguageResolver.supported(preferredLanguage);
		return supported != null ? supported : LanguageResolver.ENGLISH;
	}

	public void changeSettings(String displayName, boolean remindBorrowed, boolean notifyLentOverdue) {
		this.displayName = displayName;
		this.remindBorrowed = remindBorrowed;
		this.notifyLentOverdue = notifyLentOverdue;
	}

	// After a password change or reset; the caller has already hashed it. Every session that is
	// logged in now is ended, because one of them may belong to whoever knew the old password.
	public void changePasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
		endAllSessions();
	}

	// "Log out everywhere". The caller logs the current session in again if it should stay.
	public void endAllSessions() {
		sessionVersion++;
	}

	// The user followed a link we emailed them (sign-up confirmation or password reset), so the
	// address is theirs. The first time counts; later calls change nothing.
	public void markEmailVerified(Instant when) {
		if (emailVerifiedAt == null) {
			emailVerifiedAt = when;
		}
	}

	public boolean isEmailVerified() {
		return emailVerifiedAt != null;
	}

	public Instant getEmailVerifiedAt() {
		return emailVerifiedAt;
	}

	// Whether a notification of this kind may be emailed: the user wants it, and the address is
	// confirmed, so a typo at sign-up doesn't send a stranger reminders.
	public boolean mayBeEmailed(EmailList list) {
		return isEmailVerified() && wants(list);
	}

	public int getSessionVersion() {
		return sessionVersion;
	}

	// From an unsubscribe link: turns one kind of email off and leaves the other as it is.
	public void turnOff(EmailList list) {
		switch (list) {
			case BORROWED -> remindBorrowed = false;
			case LENT -> notifyLentOverdue = false;
		}
	}

	public boolean wants(EmailList list) {
		return switch (list) {
			case BORROWED -> remindBorrowed;
			case LENT -> notifyLentOverdue;
		};
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
