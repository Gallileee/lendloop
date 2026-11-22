package io.github.gallileee.lendloop.group;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TypoMatchTest {

	private static Item item(String name, String description) {
		return new Item(null, name, description, Category.OTHER, true);
	}

	private static List<String> similar(String search, Item... candidates) {
		return TypoMatch.similar(List.of(candidates), ItemFilter.parse(search, null, null))
			.stream()
			.map(Item::getName)
			.toList();
	}

	@ParameterizedTest(name = "\"{0}\" ~ \"{1}\": {2}")
	@CsvSource(delimiter = '|', textBlock = """
			vrtacka  | vrtacka  | true
			vrtacak  | vrtacka  | true
			vrtcka   | vrtacka  | true
			vrtaccka | vrtacka  | true
			vrtacke  | vrtacka  | true
			xrtacka  | vrtacka  | true
			vrtackx  | vrtacka  | true
			rvtacka  | vrtacka  | true
			vrtacak  | vrtacky  | false
			vtracak  | vrtacka  | false
			vrt      | vrtacka  | false
			vrtacka  | vrtackaxx| false
			ab       | ba       | true
			abc      | cba      | false
			''       | a        | true
			""")
	void oneEditIsDamerausEdit(String a, String b, boolean expected) {
		assertThat(TypoMatch.withinOneEdit(a, b)).isEqualTo(expected);
		assertThat(TypoMatch.withinOneEdit(b, a)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "\"{0}\" finds \"{1}\": {2}")
	@CsvSource(delimiter = '|', textBlock = """
			herbrt   | herbert  | true
			hebrert  | herbert  | true
			dnue     | dune     | false
			dun      | dune     | true
			dume     | dune     | false
			vrtscky  | vrtacka  | true
			vrtackou | vrtacka  | true
			knihz    | kniha    | true
			97804410 | 97804410135 | true
			97804411 | 97804410135 | false
			""")
	void longWordsMayHaveOneEditShortOnesMustStartTheWord(String typed, String word, boolean expected) {
		assertThat(TypoMatch.close(typed, word)).isEqualTo(expected);
	}

	@Test
	void findsTheThingWithOneLetterWrong() {
		assertThat(similar("vrtacak", item("Vrtačka", null), item("Kladivo", null))).containsExactly("Vrtačka");
		assertThat(similar("Příklepová vrtčka", item("Příklepová vrtačka", null))).containsExactly("Příklepová vrtačka");
		assertThat(similar("cordles", item("Cordless drill", null))).containsExactly("Cordless drill");
	}

	@Test
	void theTypedWordsAreOnePhraseInOneLine() {
		Item drill = item("Cordless drill", "Bosch, with two batteries");

		assertThat(similar("cordles bosch", drill)).isEmpty();
		assertThat(similar("two batteris", drill)).containsExactly("Cordless drill");
		// Short words aren't corrected, but must still be there (as the start of a word).
		assertThat(similar("cordles dri", drill)).containsExactly("Cordless drill");
		assertThat(similar("cordles drx", drill)).isEmpty();
		// In the same order and next to each other.
		assertThat(similar("drill cordles", drill)).isEmpty();
		assertThat(similar("bosch batteris", drill)).isEmpty();
	}

	@Test
	void nameMatchesComeBeforeDescriptionMatchesOtherwiseTheGivenOrder() {
		assertThat(similar("herbrt",
				item("Children of Dune", "By Frank Herbert"),
				item("Herbert's notebook", null),
				item("Dune", "By Frank Herbert")))
			.containsExactly("Herbert's notebook", "Children of Dune", "Dune");
	}

	@Test
	void nothingWhenNoTypedWordIsLongEnoughToCorrect() {
		// "dnue" is one swap from "dune", but four letters is too short to guess at.
		assertThat(similar("dnue", item("Dune", null))).isEmpty();
		assertThat(similar("0441", item("Dune", "0441"))).isEmpty();
		assertThat(similar("", item("Dune", null))).isEmpty();
	}

	@Test
	void twoEditsAreTooMany() {
		assertThat(similar("vtracak", item("Vrtačka", null))).isEmpty();
		assertThat(similar("herbret", item("Dune", "By Frank Herbert"))).containsExactly("Dune");
		assertThat(similar("hrebret", item("Dune", "By Frank Herbert"))).isEmpty();
	}
}
