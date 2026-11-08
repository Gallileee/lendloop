package io.github.gallileee.lendloop.group;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The "new group" form. Mutable with a setter because Spring MVC binds form fields that way.
public class GroupForm {

	@NotBlank(message = "{validation.group.name.required}")
	@Size(max = 60, message = "{validation.max60}")
	private String name = "";

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name == null ? "" : name;
	}
}
