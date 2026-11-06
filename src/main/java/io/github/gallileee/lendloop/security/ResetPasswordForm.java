package io.github.gallileee.lendloop.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

// The new password on the reset page, with the same rules as at sign-up (RegistrationForm).
public class ResetPasswordForm {

	private String token = "";

	@Size(min = 10, message = "Use at least 10 characters.")
	private String password = "";

	private String confirmPassword = "";

	@AssertTrue(message = "That password is too long (at most 72 bytes, about 70 characters).")
	public boolean isPasswordShortEnough() {
		return password.getBytes(StandardCharsets.UTF_8).length <= 72;
	}

	@AssertTrue(message = "The passwords don't match.")
	public boolean isPasswordConfirmed() {
		return password.equals(confirmPassword);
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token == null ? "" : token;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password == null ? "" : password;
	}

	public String getConfirmPassword() {
		return confirmPassword;
	}

	public void setConfirmPassword(String confirmPassword) {
		this.confirmPassword = confirmPassword == null ? "" : confirmPassword;
	}
}
