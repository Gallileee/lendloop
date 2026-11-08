package io.github.gallileee.lendloop.group;

import io.github.gallileee.lendloop.book.Isbn;
import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.SearchText;
import java.util.Arrays;
import java.util.Optional;

// What someone searches for on a group page: text in the name or description (or an ISBN), a
// category, and "only things I could borrow right now".
//
// Built from raw query parameters on purpose. A hand-edited or outdated URL (an unknown category,
// "available=yes") just drops that part of the filter, instead of answering 400 to a GET.
public record ItemFilter(String text, Category category, boolean availableNow) {

	// Longer input is cut, so a pasted paragraph can't turn into a huge LIKE pattern.
	static final int MAX_TEXT_LENGTH = 100;

	// The LIKE escape character that likePattern() uses.
	public static final char ESCAPE_CHAR = '\\';

	public static final ItemFilter NONE = new ItemFilter("", null, false);

	public ItemFilter {
		text = text == null ? "" : text;
	}

	public static ItemFilter parse(String q, String category, String available) {
		String text = q == null ? "" : q.strip().replaceAll("\\s+", " ");
		if (text.length() > MAX_TEXT_LENGTH) {
			text = text.substring(0, MAX_TEXT_LENGTH).strip();
		}
		Category parsedCategory = Arrays.stream(Category.values())
			.filter(c -> c.name().equals(category))
			.findFirst()
			.orElse(null);
		return new ItemFilter(text, parsedCategory, "true".equals(available));
	}

	public boolean isActive() {
		return !text.isEmpty() || category != null || availableNow;
	}

	// The search text as a book's ISBN-13, when it is one (e.g. read off the back cover).
	public Optional<Isbn> isbn() {
		return Isbn.parse(text);
	}

	// "%text%" for a LIKE on Item.searchText: folded the same way (lower case, no accents), with
	// the user's own % and _ taken literally. Must be used with ESCAPE_CHAR as the escape character.
	public String likePattern() {
		String escaped = SearchText.fold(text)
			.replace(String.valueOf(ESCAPE_CHAR), ESCAPE_CHAR + "" + ESCAPE_CHAR)
			.replace("%", ESCAPE_CHAR + "%")
			.replace("_", ESCAPE_CHAR + "_");
		return "%" + escaped + "%";
	}

	// For the template: the category's name for the <select>, or "" for "any".
	public String categoryName() {
		return category == null ? "" : category.name();
	}
}
