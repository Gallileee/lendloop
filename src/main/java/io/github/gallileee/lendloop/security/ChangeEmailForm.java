package io.github.gallileee.lendloop.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The "Change email" form on Settings.
public class ChangeEmailForm {

	@NotBlank(message = "{validation.email.required}")
	@Email(message = "{validation.email.invalid}")
	@Size(max = 254, message = "{validation.email.tooLong}")
	private String newEmail = "";

	private String currentPassword = "";

	public String getNewEmail() {
		return newEmail;
	}

	// Trimmed before validation, like the sign-up form.
	public void setNewEmail(String newEmail) {
		this.newEmail = newEmail == null ? "" : newEmail.trim();
	}

	public String getCurrentPassword() {
		return currentPassword;
	}

	public void setCurrentPassword(String currentPassword) {
		this.currentPassword = currentPassword == null ? "" : currentPassword;
	}
}
