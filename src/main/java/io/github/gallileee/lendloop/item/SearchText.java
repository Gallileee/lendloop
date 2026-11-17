package io.github.gallileee.lendloop.item;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Text folded for searching: lower case and without accents, so "vrtacka" finds "Vrtačka" and
// "CAFE" finds "Café". Items keep a folded copy of their name and description (Item.searchText),
// and the search box is folded the same way, so the database only has to do a plain LIKE.
//
// Done in Java rather than in SQL because H2 has no unaccent() and PostgreSQL's needs an
// extension; this way both databases behave the same and the rules are unit-tested.
public final class SearchText {

	// Combining marks left over after canonical decomposition (the háček in "č" -> "c" + "̌").
	private static final Pattern MARKS = Pattern.compile("\\p{M}+");

	// Letters that are not a base letter plus a combining mark, so decomposition leaves them as
	// they are. Only the ones that turn up in European names; the rest are searched as typed.
	private static final String[][] LETTERS = {
			{ "ł", "l" }, { "ø", "o" }, { "đ", "d" }, { "ß", "ss" }, { "æ", "ae" }, { "œ", "oe" }, { "ı", "i" } };

	// A word for stemming: letters and digits. Everything else (spaces, punctuation, "_") splits.
	private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

	private SearchText() {
	}

	// Never null; null (no description) folds to "".
	public static String fold(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		// Lower case first: some upper-case letters only gain their mark when lower-cased.
		String folded = MARKS.matcher(Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD))
			.replaceAll("");
		for (String[] letter : LETTERS) {
			folded = folded.replace(letter[0], letter[1]);
		}
		// Recompose whatever is left (e.g. Hangul), so the stored text stays in the usual form.
		return Normalizer.normalize(folded, Normalizer.Form.NFC);
	}

	// What an item is found by: its name and description, folded. The newline keeps a search
	// from matching across the two (searches never contain one: whitespace is collapsed).
	public static String forItem(String name, String description) {
		String folded = fold(name);
		String foldedDescription = fold(description);
		return foldedDescription.isEmpty() ? folded : folded + "\n" + foldedDescription;
	}

	// The words of a text, folded and cut to their Czech stem (CzechStemmer), in order.
	public static List<String> stems(String text) {
		List<String> stems = new ArrayList<>();
		Matcher word = WORD.matcher(fold(text));
		while (word.find()) {
			stems.add(CzechStemmer.stem(word.group()));
		}
		return stems;
	}

	// What an item is found by when the search is in another word form: the stems of its name and
	// description, each word after a space (" vrtack bosch"), so a search can match from the start
	// of a word. As in forItem, a newline keeps the name and the description apart.
	public static String stemsForItem(String name, String description) {
		String stems = joinedStems(name);
		String descriptionStems = joinedStems(description);
		return descriptionStems.isEmpty() ? stems : stems + "\n" + descriptionStems;
	}

	private static String joinedStems(String text) {
		StringBuilder joined = new StringBuilder();
		for (String stem : stems(text)) {
			joined.append(' ').append(stem);
		}
		return joined.toString();
	}
}
