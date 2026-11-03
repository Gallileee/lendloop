package io.github.gallileee.lendloop.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// What the Settings page posts. An unticked checkbox is not sent at all; Thymeleaf's th:field
// adds a hidden "_name" field, so Spring then binds it as false.
public class SettingsForm {

	@NotBlank(message = "Enter the name your friends know you by.")
	@Size(max = 60, message = "Use at most 60 characters.")
	private String displayName = "";

	private boolean remindBorrowed;

	private boolean notifyLentOverdue;

	static SettingsForm of(AppUser user) {
		SettingsForm form = new SettingsForm();
		form.setDisplayName(user.getDisplayName());
		form.setRemindBorrowed(user.isRemindBorrowed());
		form.setNotifyLentOverdue(user.isNotifyLentOverdue());
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
}
