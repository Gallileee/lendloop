package io.github.gallileee.lendloop.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class CzechStemmerTest {

	// The forms of one word, folded as SearchText leaves them, all meet at one stem.
	static Stream<Arguments> wordForms() {
		return Stream.of(
				// feminine -a, with k -> c before -e ("vrtačce")
				Arguments.of("vrtack", List.of("vrtacka", "vrtacky", "vrtacce", "vrtacku", "vrtackou", "vrtackam",
						"vrtackach", "vrtackami")),
				// masculine inanimate, with k -> c before -ích ("žebřících")
				Arguments.of("zebrik", List.of("zebrik", "zebriku", "zebrikem", "zebriky", "zebricich",
						"zebrikum")),
				// h -> z before -e ("knize")
				Arguments.of("knih", List.of("kniha", "knihy", "knize", "knihu", "knihou", "knihami", "knihach")),
				// the "e" that drops out ("stolek" / "stolku")
				Arguments.of("stolk", List.of("stolek", "stolku", "stolkem", "stolky", "stolkum")),
				// neuter
				Arguments.of("kol", List.of("kolo", "kola", "kolem", "kolu", "kolech")),
				// adjectives (the "e" before the last letter goes too, on both sides alike)
				Arguments.of("priklp", List.of("priklepova", "priklepove", "priklepovou", "priklepovy",
						"priklepovych")),
				// a possessive adjective comes down to the owner's name
				Arguments.of("petr", List.of("petr", "petra", "petrova", "petrovo", "petrovy")));
	}

	@ParameterizedTest
	@MethodSource("wordForms")
	void formsOfAWordShareAStem(String stem, List<String> forms) {
		assertThat(forms).allSatisfy(form -> assertThat(CzechStemmer.stem(form)).as(form).isEqualTo(stem));
	}

	@ParameterizedTest
	@CsvSource({ "pes", "psa", "kun", "a", "do" })
	void shortWordsAreLeftAsTheyAre(String word) {
		assertThat(CzechStemmer.stem(word)).isEqualTo(word);
	}

	@ParameterizedTest
	@CsvSource({ "100", "2024", "a4", "v2a" })
	void wordsWithDigitsAreLeftAsTheyAre(String word) {
		assertThat(CzechStemmer.stem(word)).isEqualTo(word);
	}

	@Test
	void longEndingsNeedALongEnoughWord() {
		// "-ech" only comes off a word longer than five letters: "ulech" keeps it ...
		assertThat(CzechStemmer.stem("ulech")).isEqualTo("ulech");
		// ... but "kolech" (the wheels, locative) loses it.
		assertThat(CzechStemmer.stem("kolech")).isEqualTo("kol");
		// The longest ending that fits wins: "-atech", not "-ech" or "-h".
		assertThat(CzechStemmer.stem("kuratech")).isEqualTo("kur");
	}

	@Test
	void differentWordsKeepDifferentStems() {
		assertThat(CzechStemmer.stem("vrtacka")).isNotEqualTo(CzechStemmer.stem("vrtak"));
		assertThat(CzechStemmer.stem("stan")).isNotEqualTo(CzechStemmer.stem("stul"));
		assertThat(CzechStemmer.stem("kolo")).isNotEqualTo(CzechStemmer.stem("kolobezka"));
	}
}
