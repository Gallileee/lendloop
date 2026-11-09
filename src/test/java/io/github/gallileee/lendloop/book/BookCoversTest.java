package io.github.gallileee.lendloop.book;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BookCoversTest {

	private final BookCovers covers = new BookCovers("https://covers.openlibrary.org");

	@Test
	void aStoredIsbnGivesTheMediumCoverByIsbn() {
		assertThat(covers.url("9780441013593")).isEqualTo("https://covers.openlibrary.org/b/isbn/9780441013593-M.jpg");
	}

	@Test
	void anIsbn10IsNormalisedToIsbn13() {
		assertThat(covers.url("0-441-01359-7")).isEqualTo("https://covers.openlibrary.org/b/isbn/9780441013593-M.jpg");
	}

	@Test
	void noIsbnOrAnInvalidOneGivesNoUrl() {
		assertThat(covers.url(null)).isNull();
		assertThat(covers.url("")).isNull();
		assertThat(covers.url("9780441013590")).isNull(); // wrong check digit
		assertThat(covers.url("x\" onerror=\"alert(1)")).isNull();
	}

	@Test
	void aTrailingSlashInTheBaseUrlIsDropped() {
		assertThat(new BookCovers("https://covers.example/ ").url("9780441013593"))
			.isEqualTo("https://covers.example/b/isbn/9780441013593-M.jpg");
	}

	@Test
	void anEmptyBaseUrlTurnsCoversOff() {
		BookCovers off = new BookCovers("");
		assertThat(off.enabled()).isFalse();
		assertThat(off.url("9780441013593")).isNull();
		assertThat(new BookCovers(null).enabled()).isFalse();
	}
}
