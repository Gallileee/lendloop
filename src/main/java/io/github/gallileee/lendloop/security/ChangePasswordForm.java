package io.github.gallileee.lendloop.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

// The "Change password" form on Settings. The new password follows the sign-up rules.
public class ChangePasswordForm {

	private String currentPassword = "";

	@Size(min = 10, message = "Use at least 10 characters.")
	private String newPassword = "";

	private String confirmPassword = "";

	@AssertTrue(message = "That password is too long (at most 72 bytes, about 70 characters).")
	public boolean isNewPasswordShortEnough() {
		return newPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
	}

	@AssertTrue(message = "The new passwords don't match.")
	public boolean isNewPasswordConfirmed() {
		return newPassword.equals(confirmPassword);
	}

	public String getCurrentPassword() {
		return currentPassword;
	}

	public void setCurrentPassword(String currentPassword) {
		this.currentPassword = currentPassword == null ? "" : currentPassword;
	}

	public String getNewPassword() {
		return newPassword;
	}

	public void setNewPassword(String newPassword) {
		this.newPassword = newPassword == null ? "" : newPassword;
	}

	public String getConfirmPassword() {
		return confirmPassword;
	}

	public void setConfirmPassword(String confirmPassword) {
		this.confirmPassword = confirmPassword == null ? "" : confirmPassword;
	}
}
