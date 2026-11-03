package io.github.gallileee.lendloop.item;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// The add / edit item form. Mutable with setters because Spring MVC binds form fields that way.
public class ItemForm {

	@NotBlank(message = "Give the item a name.")
	@Size(max = 100, message = "Use at most 100 characters.")
	private String name = "";

	@Size(max = 1000, message = "Use at most 1000 characters.")
	private String description = "";

	@NotNull(message = "Pick a category.")
	private Category category = Category.OTHER;

	private boolean available = true;

	public static ItemForm from(Item item) {
		ItemForm form = new ItemForm();
		form.setName(item.getName());
		form.setDescription(item.getDescription());
		form.setCategory(item.getCategory());
		form.setAvailable(item.isAvailable());
		return form;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name == null ? "" : name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description == null ? "" : description;
	}

	public Category getCategory() {
		return category;
	}

	public void setCategory(Category category) {
		this.category = category;
	}

	public boolean isAvailable() {
		return available;
	}

	public void setAvailable(boolean available) {
		this.available = available;
	}
}
