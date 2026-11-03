package io.github.gallileee.lendloop.user;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

// What the sign-up page posts. Mutable with setters because Spring MVC binds form fields that way.
public class RegistrationForm {

	@NotBlank(message = "Enter your email.")
	@Email(message = "Enter a valid email address.")
	@Size(max = 254, message = "That email is too long.")
	private String email = "";

	@NotBlank(message = "Enter the name your friends know you by.")
	@Size(max = 60, message = "Use at most 60 characters.")
	private String displayName = "";

	// 10+ characters, no composition rules (NIST SP 800-63B).
	@Size(min = 10, message = "Use at least 10 characters.")
	private String password = "";

	private String confirmPassword = "";

	// BCrypt only reads the first 72 bytes, and Spring Security rejects longer input.
	// Counted in UTF-8 bytes, because characters like "ř" take two.
	@AssertTrue(message = "That password is too long (at most 72 bytes, about 70 characters).")
	public boolean isPasswordShortEnough() {
		return password.getBytes(StandardCharsets.UTF_8).length <= 72;
	}

	@AssertTrue(message = "The passwords don't match.")
	public boolean isPasswordConfirmed() {
		return password.equals(confirmPassword);
	}

	public String getEmail() {
		return email;
	}

	// Trimmed before validation, so a pasted address with a trailing space still passes @Email.
	public void setEmail(String email) {
		this.email = email == null ? "" : email.trim();
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName == null ? "" : displayName;
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
