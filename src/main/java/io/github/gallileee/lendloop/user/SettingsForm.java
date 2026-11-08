package io.github.gallileee.lendloop.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// What the Settings page posts. An unticked checkbox is not sent at all; Thymeleaf's th:field
// adds a hidden "_name" field, so Spring then binds it as false.
public class SettingsForm {

	@NotBlank(message = "{validation.displayName.required}")
	@Size(max = 60, message = "{validation.max60}")
	private String displayName = "";

	private boolean remindBorrowed;

	private boolean notifyLentOverdue;

	// The language of the emails: one of LanguageResolver.SUPPORTED. Left out (null), the
	// stored language stays as it is.
	@Pattern(regexp = "en|cs", message = "{validation.language}")
	private String emailLanguage;

	static SettingsForm of(AppUser user) {
		SettingsForm form = new SettingsForm();
		form.setDisplayName(user.getDisplayName());
		form.setRemindBorrowed(user.isRemindBorrowed());
		form.setNotifyLentOverdue(user.isNotifyLentOverdue());
		form.setEmailLanguage(user.getLocale().getLanguage());
		return form;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName == null ? "" : displayName;
	}

	public boolean isRemindBorrowed() {
		return remindBorrowed;
	}

	public void setRemindBorrowed(boolean remindBorrowed) {
		this.remindBorrowed = remindBorrowed;
	}

	public boolean isNotifyLentOverdue() {
		return notifyLentOverdue;
	}

	public void setNotifyLentOverdue(boolean notifyLentOverdue) {
		this.notifyLentOverdue = notifyLentOverdue;
	}

	public String getEmailLanguage() {
		return emailLanguage;
	}

	public void setEmailLanguage(String emailLanguage) {
		this.emailLanguage = emailLanguage;
	}
}
