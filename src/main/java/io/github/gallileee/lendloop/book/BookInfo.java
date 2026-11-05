package io.github.gallileee.lendloop.book;

import java.util.List;

// What a catalogue knows about one edition of a book. Text comes from an outside service,
// so it is only ever shown escaped (Thymeleaf th:text / th:value) and is cut to the
// item form's limits.
public record BookInfo(String title, String subtitle, List<String> authors, String publisher, String publishDate) {

	static final int MAX_NAME = 100;
	static final int MAX_DESCRIPTION = 1000;

	public BookInfo {
		authors = authors == null ? List.of() : List.copyOf(authors);
	}

	// "Title: Subtitle", for the item name.
	public String itemName() {
		String name = isBlank(subtitle) ? title.strip() : title.strip() + ": " + subtitle.strip();
		return cut(name, MAX_NAME);
	}

	// "By Roald Dahl · Puffin, 1988", leaving out whatever is unknown. Empty if nothing is.
	public String itemDescription() {
		StringBuilder text = new StringBuilder();
		if (!authors.isEmpty()) {
			text.append("By ").append(String.join(", ", authors));
		}
		String published = join(publisher, publishDate);
		if (!published.isEmpty()) {
			text.append(text.isEmpty() ? "" : " · ").append(published);
		}
		return cut(text.toString(), MAX_DESCRIPTION);
	}

	private static String join(String a, String b) {
		if (isBlank(a)) {
			return isBlank(b) ? "" : b.strip();
		}
		return isBlank(b) ? a.strip() : a.strip() + ", " + b.strip();
	}

	private static boolean isBlank(String text) {
		return text == null || text.isBlank();
	}

	private static String cut(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 1).stripTrailing() + "…";
	}
}
