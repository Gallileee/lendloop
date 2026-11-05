package io.github.gallileee.lendloop.group;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gallileee.lendloop.item.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ItemFilterTest {

	@Test
	void missingParametersMeanNoFilter() {
		ItemFilter filter = ItemFilter.parse(null, null, null);

		assertThat(filter).isEqualTo(ItemFilter.NONE);
		assertThat(filter.isActive()).isFalse();
	}

	@Test
	void textIsTrimmedAndInnerWhitespaceCollapsed() {
		assertThat(ItemFilter.parse("  dune \t  messiah\n", null, null).text()).isEqualTo("dune messiah");
		assertThat(ItemFilter.parse("   ", null, null).isActive()).isFalse();
	}

	@Test
	void overlongTextIsCut() {
		String text = ItemFilter.parse("a".repeat(500), null, null).text();

		assertThat(text).hasSize(ItemFilter.MAX_TEXT_LENGTH);
	}

	@Test
	void categoryIsMatchedByExactNameAndUnknownOnesAreIgnored() {
		assertThat(ItemFilter.parse(null, "BOOK", null).category()).isEqualTo(Category.BOOK);
		assertThat(ItemFilter.parse(null, "book", null).category()).isNull();
		assertThat(ItemFilter.parse(null, "SPACESHIP", null).category()).isNull();
		assertThat(ItemFilter.parse(null, "", null).isActive()).isFalse();
	}

	@ParameterizedTest
	@CsvSource({ "true, true", "false, false", "yes, false", "'', false" })
	void onlyTrueAsksForThingsThatAreFreeNow(String value, boolean expected) {
		assertThat(ItemFilter.parse(null, null, value).availableNow()).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"Dune        | %dune%",
			"100%        | %100\\%%",
			"snake_case  | %snake\\_case%",
			"back\\slash | %back\\\\slash%" })
	void likePatternIsLowerCaseAndEscapesWildcards(String text, String pattern) {
		assertThat(new ItemFilter(text, null, false).likePattern()).isEqualTo(pattern);
	}

	@Test
	void anIsbnInAnyFormatIsRecognised() {
		assertThat(ItemFilter.parse("0-441-01359-7", null, null).isbn()).hasValueSatisfying(
				isbn -> assertThat(isbn.value()).isEqualTo("9780441013593"));
		assertThat(ItemFilter.parse("dune", null, null).isbn()).isEmpty();
	}
}
