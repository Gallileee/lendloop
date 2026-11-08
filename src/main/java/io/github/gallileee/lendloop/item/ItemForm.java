package io.github.gallileee.lendloop.item;

import io.github.gallileee.lendloop.book.ValidIsbn;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// The add / edit item form. Mutable with setters because Spring MVC binds form fields that way.
public class ItemForm {

	@NotBlank(message = "{validation.item.name.required}")
	@Size(max = 100, message = "{validation.max100}")
	private String name = "";

	@Size(max = 1000, message = "{validation.max1000}")
	private String description = "";

	@NotNull(message = "{validation.item.category.required}")
	private Category category = Category.OTHER;

	private boolean available = true;

	// As typed; the service stores it normalised to ISBN-13.
	@ValidIsbn
	@Size(max = 30, message = "{validation.max30}")
	private String isbn = "";

	public static ItemForm from(Item item) {
		ItemForm form = new ItemForm();
		form.setName(item.getName());
		form.setDescription(item.getDescription());
		form.setCategory(item.getCategory());
		form.setAvailable(item.isAvailable());
		form.setIsbn(item.getIsbn());
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

	public String getIsbn() {
		return isbn;
	}

	public void setIsbn(String isbn) {
		this.isbn = isbn == null ? "" : isbn.trim();
	}
}
