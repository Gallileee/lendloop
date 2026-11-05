package io.github.gallileee.lendloop.book;

import java.util.Optional;

// Finds a book by ISBN in an online catalogue. An interface so tests (and maybe another
// catalogue later) can stand in for Open Library.
public interface BookLookup {

	// Empty when the catalogue doesn't know the ISBN.
	// Throws BookLookupUnavailableException when it can't be asked (offline, slow, broken).
	Optional<BookInfo> find(Isbn isbn);

	class BookLookupUnavailableException extends RuntimeException {

		public BookLookupUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
