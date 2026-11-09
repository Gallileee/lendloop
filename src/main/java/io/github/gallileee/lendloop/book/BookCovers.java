package io.github.gallileee.lendloop.book;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Links to book covers in the Open Library Covers API
// (https://openlibrary.org/dev/docs/api/covers): GET /b/isbn/{isbn}-M.jpg. The viewer's browser
// loads them, so the server makes no requests, but Open Library sees the viewer's IP address;
// that is why covers are opt-in (AppUser.showBookCovers). A book without a cover gets a blank
// 1x1 image rather than a 404. Lookups by ISBN are limited to 100 per IP every 5 minutes, which
// is far more than one person scrolls through.
//
// Used from templates as ${@bookCovers.url(item.isbn)}.
@Component("bookCovers")
public class BookCovers {

	private final String baseUrl;

	// An empty base URL turns covers off for everyone.
	public BookCovers(@Value("${lendloop.covers.base-url:}") String baseUrl) {
		String trimmed = baseUrl == null ? "" : baseUrl.strip();
		this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}

	public boolean enabled() {
		return !baseUrl.isEmpty();
	}

	// The medium-size cover for this ISBN, or null if covers are off or the ISBN isn't valid.
	// Stored ISBNs are already normalised; parsing again keeps anything else out of the URL.
	public String url(String isbn) {
		if (!enabled() || isbn == null) {
			return null;
		}
		return Isbn.parse(isbn).map(valid -> baseUrl + "/b/isbn/" + valid.value() + "-M.jpg").orElse(null);
	}
}
