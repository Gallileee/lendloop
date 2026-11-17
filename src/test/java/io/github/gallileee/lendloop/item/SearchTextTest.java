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
	void stemsAreFoldedWordsInOrder() {
		assertThat(SearchText.stems("Příklepová VRTAČKA, 2 kufříky!")).containsExactly("priklp", "vrtack", "2", "kufrik");
		assertThat(SearchText.stems("snake_case 100%")).containsExactly("snak", "cas", "100");
		assertThat(SearchText.stems(" , ")).isEmpty();
		assertThat(SearchText.stems(null)).isEmpty();
	}

	@Test
	void stemsForAnItemStartEachWordWithASpaceAndKeepNameAndDescriptionApart() {
		assertThat(SearchText.stemsForItem("Příklepová vrtačka", "Bosch, s kufříkem"))
			.isEqualTo(" priklp vrtack\n bosch s kufrik");
		assertThat(SearchText.stemsForItem("Stan", null)).isEqualTo(" stan");
		assertThat(SearchText.stemsForItem("Stan", "")).isEqualTo(" stan");
	}

	@Test
	void stemsForAnItemFitTheColumnWhateverTheText() {
		// Many short words (each one gains the space before it) and letters that double when folded.
		String name = "ß".repeat(Item.SEARCH_TEXT_LENGTH / 4);
		String description = "ß ".repeat(Item.SEARCH_TEXT_LENGTH / 8);

		String folded = SearchText.forItem(name, description);
		assertThat(folded.length()).isLessThanOrEqualTo(Item.SEARCH_TEXT_LENGTH);
		assertThat(SearchText.stemsForItem(name, description).length())
			.isLessThanOrEqualTo(folded.length() + 2)
			.isLessThanOrEqualTo(Item.SEARCH_STEMS_LENGTH);
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
