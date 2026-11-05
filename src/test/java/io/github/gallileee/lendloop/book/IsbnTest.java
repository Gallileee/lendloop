package io.github.gallileee.lendloop.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IsbnTest {

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource(delimiter = '|', value = {
			"9780140328721       | 9780140328721",
			"978-0-14-032872-1   | 9780140328721",
			"' 978 0 14 032872 1 ' | 9780140328721",
			// ISBN-10 of the same book becomes the same ISBN-13
			"0140328726          | 9780140328721",
			"0-14-032872-6       | 9780140328721",
			// ISBN-10 whose check digit is X (= 10), upper or lower case
			"080442957X          | 9780804429573",
			"0-8044-2957-x       | 9780804429573",
			// 979 prefix, which has no ISBN-10
			"979-10-323-0082-4   | 9791032300824" })
	void acceptsIsbn10AndIsbn13AsTyped(String typed, String isbn13) {
		assertThat(Isbn.parse(typed)).hasValue(new Isbn(isbn13));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {
			"9780140328722", // wrong check digit
			"0140328727", // wrong ISBN-10 check digit
			"9770140328721", // 977 is an ISSN prefix, not an ISBN
			"978014032872", // 12 digits
			"97801403287211", // 14 digits
			"X140328726", // X only allowed last
			"978014032872X", // no X in ISBN-13
			"978.0.14.032872.1", // only spaces and hyphens are ignored
			"abc" })
	void rejectsAnythingElse(String typed) {
		assertThat(Isbn.parse(typed)).isEmpty();
	}

	@Test
	void theRecordItselfOnlyHoldsValidIsbn13() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Isbn("9780140328722"));
		assertThatIllegalArgumentException().isThrownBy(() -> new Isbn("0140328726"));
		assertThat(new Isbn("9780140328721")).hasToString("9780140328721");
	}
}
