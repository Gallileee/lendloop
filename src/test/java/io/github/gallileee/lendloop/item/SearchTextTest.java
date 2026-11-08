package io.github.gallileee.lendloop.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SearchTextTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"Vrtačka                 | vrtacka",
			"ŽLUŤOUČKÝ KŮŇ           | zlutoucky kun",
			"Příklepová vrtačka Bosch | priklepova vrtacka bosch",
			"Café crème              | cafe creme",
			"Łódź                    | lodz",
			"Øresund                 | oresund",
			"Straße                  | strasse",
			"İstanbul                | istanbul",
			"100% & snake_case       | 100% & snake_case" })
	void foldsCaseAndAccents(String text, String folded) {
		assertThat(SearchText.fold(text)).isEqualTo(folded);
	}

	@Test
	void decomposedInputFoldsLikePrecomposed() {
		// macOS and some keyboards send "c" followed by a combining háček instead of "č".
		String decomposed = Normalizer.normalize("Vrtačka", Normalizer.Form.NFD);

		assertThat(decomposed).isNotEqualTo("Vrtačka");
		assertThat(SearchText.fold(decomposed)).isEqualTo("vrtacka");
	}

	@Test
	void nothingFoldsToEmpty() {
		assertThat(SearchText.fold(null)).isEmpty();
		assertThat(SearchText.fold("")).isEmpty();
	}

	@Test
	void anItemIsFoundByItsNameAndDescriptionKeptApart() {
		assertThat(SearchText.forItem("Vrtačka", "Půjčím jen s vrtáky")).isEqualTo("vrtacka\npujcim jen s vrtaky");
		assertThat(SearchText.forItem("Stan", null)).isEqualTo("stan");
	}

	@Test
	void theFoldedTextFitsTheColumnEvenWhenEveryLetterDoubles() {
		String text = SearchText.forItem("ß".repeat(100), "ß".repeat(1000));

		assertThat(text).hasSize(2201);
		assertThat(text.length()).isLessThanOrEqualTo(Item.SEARCH_TEXT_LENGTH);
	}

	@Test
	void itemsKeepTheirSearchTextInStep() {
		Item item = new Item(null, "Vrtačka", null, Category.TOOL, true);
		assertThat(item.getSearchText()).isEqualTo("vrtacka");

		item.update("Šroubovák", "Křížový", Category.TOOL, true);

		assertThat(item.getSearchText()).isEqualTo("sroubovak\nkrizovy");
	}
}
