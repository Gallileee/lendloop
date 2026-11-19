package io.github.gallileee.lendloop.group;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gallileee.lendloop.book.Isbn;
import io.github.gallileee.lendloop.group.SearchRank.Tier;
import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SearchRankTest {

	private static Item item(String name, String description) {
		return new Item(null, name, description, Category.OTHER, true);
	}

	private static Tier tier(String search, String name, String description) {
		return SearchRank.tier(item(name, description), ItemFilter.parse(search, null, null));
	}

	@ParameterizedTest(name = "\"{0}\" in \"{1}\" / \"{2}\" -> {3}")
	@CsvSource(delimiter = '|', nullValues = "-", textBlock = """
			dune               | Dune                    | By Frank Herbert       | EXACT
			DÚNE               | Dune                    | -                      | EXACT
			vrtačka            | Vrtačka                 | -                      | EXACT
			dune               | Dune, Kim's             | -                      | NAME_STARTS_WITH_WORDS
			dune messiah       | Dune Messiah (2nd ed.)  | -                      | NAME_STARTS_WITH_WORDS
			dune               | Children of Dune        | -                      | NAME_HAS_WORDS
			vrtačka            | Aku vrtačka             | -                      | NAME_HAS_WORDS
			vrt                | Vrtačka                 | -                      | NAME_HAS_TEXT
			dun                | Dune                    | -                      | NAME_HAS_TEXT
			une                | Dune                    | -                      | NAME_HAS_TEXT
			vrtačky            | Příklepová vrtačka      | -                      | NAME_HAS_WORD_FORMS
			příklepové vrt     | Příklepová vrtačka      | -                      | NAME_HAS_WORD_FORMS
			herbert            | Dune                    | By Frank Herbert       | DESCRIPTION_HAS_WORDS
			herb               | Dune                    | By Frank Herbert       | DESCRIPTION_OTHER
			vrtačky            | Sada vrtáků             | Hodí se do vrtačky     | DESCRIPTION_HAS_WORDS
			vrtačkou           | Sada vrtáků             | Hodí se do vrtačky     | DESCRIPTION_OTHER
			""")
	void tiers(String search, String name, String description, Tier expected) {
		assertThat(tier(search, name, description)).isEqualTo(expected);
	}

	@Test
	void aTypedIsbnIsAnExactMatchForItsBook() {
		Item dune = item("Dune", null);
		dune.setIsbn(Isbn.parse("9780441013593").orElseThrow());

		assertThat(SearchRank.tier(dune, ItemFilter.parse("0-441-01359-7", null, null))).isEqualTo(Tier.EXACT);
	}

	@Test
	void wholeWordsNeedNoLetterOrDigitOnEitherSide() {
		assertThat(SearchRank.hasWholeWords("children of dune", "dune")).isTrue();
		assertThat(SearchRank.hasWholeWords("dune (2nd ed.)", "dune")).isTrue();
		assertThat(SearchRank.hasWholeWords("dunes", "dune")).isFalse();
		assertThat(SearchRank.hasWholeWords("sand dune2", "dune")).isFalse();
		// A later occurrence counts even if the first one is inside a word.
		assertThat(SearchRank.hasWholeWords("dunes and dune", "dune")).isTrue();
		assertThat(SearchRank.hasWholeWords("", "dune")).isFalse();
	}

	@Test
	void bestFirstSortsByTierAndKeepsTheGivenOrderWithinATier() {
		List<Item> matches = new ArrayList<>(List.of(
				item("Children of Dune", "By Frank Herbert"),
				item("Dune Messiah", null),
				item("Dune", null),
				item("Dunes of Namibia", null),
				item("Sand book", "Mentions Dune once"),
				item("God Emperor of Dune", null)));

		SearchRank.bestFirst(matches, ItemFilter.parse("dune", null, null));

		assertThat(matches).extracting(Item::getName)
			.containsExactly("Dune", "Dune Messiah", "Children of Dune", "God Emperor of Dune", "Dunes of Namibia",
					"Sand book");
	}

	@Test
	void withoutTypedTextTheOrderIsLeftAlone() {
		List<Item> matches = new ArrayList<>(List.of(item("b", null), item("a", null)));

		SearchRank.bestFirst(matches, ItemFilter.parse(null, "BOOK", "true"));

		assertThat(matches).extracting(Item::getName).containsExactly("b", "a");
	}
}
