package io.github.gallileee.lendloop.item;

import io.github.gallileee.lendloop.book.Isbn;
import io.github.gallileee.lendloop.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Instant;

// Something a user owns and may lend to friends.
@Entity
public class Item {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private AppUser owner;

	@Column(nullable = false)
	private String name;

	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Category category;

	// Whether friends can ask to borrow it (the owner may keep some things private for now).
	@Column(nullable = false)
	private boolean available = true;

	// ISBN-13 digits for books, if the owner gave one.
	private String isbn;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	// Name and description folded for the search box (see SearchText), kept in step by update().
	@Column(name = "search_text", nullable = false, length = SEARCH_TEXT_LENGTH)
	private String searchText;

	// Room for the name and description even if folding doubles every letter ("ß" -> "ss").
	public static final int SEARCH_TEXT_LENGTH = 2500;

	protected Item() {
		// for JPA
	}

	public Item(AppUser owner, String name, String description, Category category, boolean available) {
		this.owner = owner;
		this.createdAt = Instant.now();
		update(name, description, category, available);
	}

	public final void update(String name, String description, Category category, boolean available) {
		this.name = name;
		this.description = description;
		this.category = category;
		this.available = available;
		this.searchText = SearchText.forItem(name, description);
	}

	public Long getId() {
		return id;
	}

	public AppUser getOwner() {
		return owner;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public Category getCategory() {
		return category;
	}

	public boolean isAvailable() {
		return available;
	}

	public String getIsbn() {
		return isbn;
	}

	public void setIsbn(Isbn isbn) {
		this.isbn = isbn == null ? null : isbn.value();
	}

	public String getSearchText() {
		return searchText;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
