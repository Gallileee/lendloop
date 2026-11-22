package io.github.gallileee.lendloop.group;

import io.github.gallileee.lendloop.item.CzechStemmer;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.SearchText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The group search's second try: when the typed text finds nothing, things whose name (or
// description) has the same words with one letter wrong ("vrtacak" -> "Vrtačka", "dnue" -> "Dune").
//
// Only a fallback, so it never adds look-alikes to a search that already found something: "Dune"
// shouldn't bring "Dunes" and "Duke" along. And only for words of 5 letters or more: one edit is a
// fifth of such a word, but a quarter of "cat" or "dune" ("cast", "date", "line" are one edit from
// those). That is about the line Elasticsearch's AUTO fuzziness draws (exact up to 2 letters, one
// edit for 3-5, two from 6), shifted up one letter because the fallback runs on whole groups
// rather than on a ranked index.
//
// One edit is Damerau's: a letter left out, added, changed, or two neighbours swapped. Damerau
// (1964) found that over 80 % of misspellings are one such edit.
final class TypoMatch {

	// Shorter typed words have to match the start of a word exactly, as in the plain search.
	static final int MIN_FUZZY_LENGTH = 5;

	private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

	private TypoMatch() {
	}

	// The things among `candidates` that the typed text finds with one wrong letter per long word,
	// name matches first, otherwise in the given order. Empty when the text has no word long
	// enough to be corrected (then any "similar" result would only be a looser prefix search).
	static List<Item> similar(List<Item> candidates, ItemFilter filter) {
		List<String> typed = words(SearchText.fold(filter.text()));
		if (typed.stream().noneMatch(TypoMatch::correctable)) {
			return List.of();
		}
		List<Item> found = new ArrayList<>();
		for (Item item : candidates) {
			if (where(item, typed) != Where.NOWHERE) {
				found.add(item);
			}
		}
		found.sort(Comparator.comparing(item -> where(item, typed)));
		return found;
	}

	enum Where {
		NAME, DESCRIPTION, NOWHERE
	}

	// Where the typed words have close words, one after another and in the same order (one
	// phrase, as in the plain search): in the name, or in the description. A search doesn't run
	// from one into the other.
	static Where where(Item item, List<String> typed) {
		String[] text = lines(item.getSearchText());
		if (hasPhrase(words(text[0]), typed)) {
			return Where.NAME;
		}
		if (hasPhrase(words(text[1]), typed)) {
			return Where.DESCRIPTION;
		}
		return Where.NOWHERE;
	}

	private static boolean hasPhrase(List<String> words, List<String> typed) {
		for (int start = 0; start + typed.size() <= words.size(); start++) {
			if (phraseAt(words, start, typed)) {
				return true;
			}
		}
		return false;
	}

	private static boolean phraseAt(List<String> words, int start, List<String> typed) {
		for (int i = 0; i < typed.size(); i++) {
			if (!close(typed.get(i), words.get(start + i))) {
				return false;
			}
		}
		return true;
	}

	// Whether a typed word (folded) finds a word of the thing (folded): a long word with one edit,
	// either as typed or, when it has a Czech ending, as its stem against the word's stem (a typo
	// in another word form, as in "vrtšcky" for "vrtačka"); a short word or one with digits only
	// as the start of the word. A typed word without an ending isn't compared by stem: "vrták"
	// (a drill bit) is one letter from the stem of "vrtačka" (a drill), but a different thing.
	static boolean close(String typed, String word) {
		if (!correctable(typed)) {
			return word.startsWith(typed);
		}
		if (withinOneEdit(typed, word)) {
			return true;
		}
		String typedStem = CzechStemmer.stem(typed);
		String wordStem = CzechStemmer.stem(word);
		return typedStem.length() < typed.length() && typedStem.length() >= MIN_FUZZY_LENGTH - 1
				&& withinOneEdit(typedStem, wordStem);
	}

	private static boolean correctable(String word) {
		return word.length() >= MIN_FUZZY_LENGTH && word.chars().noneMatch(Character::isDigit);
	}

	// Damerau's one edit (optimal string alignment, distance <= 1) in one pass: equal, one letter
	// changed, two neighbours swapped, or one letter more on either side.
	static boolean withinOneEdit(String a, String b) {
		if (Math.abs(a.length() - b.length()) > 1) {
			return false;
		}
		int start = 0;
		while (start < a.length() && start < b.length() && a.charAt(start) == b.charAt(start)) {
			start++;
		}
		if (start == a.length() && start == b.length()) {
			return true;
		}
		if (a.length() == b.length()) {
			String restA = a.substring(start + 1);
			String restB = b.substring(start + 1);
			if (restA.equals(restB)) {
				return true;
			}
			// A swap: "ab" for "ba" here, the rest equal.
			return start + 1 < a.length() && a.charAt(start) == b.charAt(start + 1)
					&& a.charAt(start + 1) == b.charAt(start) && a.substring(start + 2).equals(b.substring(start + 2));
		}
		// One letter more in the longer word at `start`.
		String longer = a.length() > b.length() ? a : b;
		String shorter = longer == a ? b : a;
		return longer.substring(start + 1).equals(shorter.substring(start));
	}

	static List<String> words(String folded) {
		List<String> words = new ArrayList<>();
		Matcher word = WORD.matcher(folded);
		while (word.find()) {
			words.add(word.group());
		}
		return words;
	}

	// Item.searchText: the name, then a newline and the description (if any).
	private static String[] lines(String searchText) {
		int newline = searchText.indexOf('\n');
		return newline < 0 ? new String[] { searchText, "" }
				: new String[] { searchText.substring(0, newline), searchText.substring(newline + 1) };
	}
}
