package io.github.gallileee.lendloop.group;

import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.SearchText;
import java.util.Comparator;
import java.util.List;

// The order of search results on the group page: the closest matches first. With word forms
// (search_stems) and matches anywhere in the description, a search for "vrtačka" also finds
// "Sada vrtáků" whose description mentions "do vrtačky"; the drill itself should still come first.
//
// Done in Java on the matches the query returned, rather than as an ORDER BY CASE in SQL: the
// group page isn't paged (a group has tens of things, not thousands), the rules work on the same
// folded text and stems SearchText makes, and they are unit-tested without a database. If the page
// ever gets paged, the tiers have to move into the query, or page 2 would be ranked on its own.
final class SearchRank {

	// Lower is better. Within a tier the query's order (name, then id) is kept.
	enum Tier {
		// The whole name is the search ("dune" -> "Dune"), or the search is the book's ISBN.
		EXACT,
		// The name starts with the search as whole words ("dune" -> "Dune, Kim's").
		NAME_STARTS_WITH_WORDS,
		// The search is whole words somewhere in the name ("dune" -> "Children of Dune").
		NAME_HAS_WORDS,
		// The search is part of a word in the name ("vrt" -> "Vrtačka", "drill" -> "Drills").
		NAME_HAS_TEXT,
		// The name has the same words in another Czech form ("vrtačky" -> "Vrtačka").
		NAME_HAS_WORD_FORMS,
		// The search is whole words in the description ("herbert" -> "By Frank Herbert").
		DESCRIPTION_HAS_WORDS,
		// Anything else the query found: part of a word, or another word form, in the description.
		DESCRIPTION_OTHER
	}

	private SearchRank() {
	}

	// Sorts matches in place, best first; leaves them as they are when nothing was typed. The sort
	// is stable, so equally good matches stay in the order the query returned them.
	static void bestFirst(List<Item> matches, ItemFilter filter) {
		if (filter.text().isEmpty()) {
			return;
		}
		Query query = Query.of(filter);
		matches.sort(Comparator.comparing(item -> tier(item, query)));
	}

	static Tier tier(Item item, ItemFilter filter) {
		return tier(item, Query.of(filter));
	}

	private static Tier tier(Item item, Query query) {
		if (query.isbn != null && query.isbn.equals(item.getIsbn())) {
			return Tier.EXACT;
		}
		String name = firstLine(item.getSearchText());
		if (!query.text.isEmpty()) {
			if (name.equals(query.text)) {
				return Tier.EXACT;
			}
			if (name.startsWith(query.text) && endsWord(name, query.text.length())) {
				return Tier.NAME_STARTS_WITH_WORDS;
			}
			if (hasWholeWords(name, query.text)) {
				return Tier.NAME_HAS_WORDS;
			}
			if (name.contains(query.text)) {
				return Tier.NAME_HAS_TEXT;
			}
		}
		if (query.stems != null && firstLine(item.getSearchStems()).contains(query.stems)) {
			return Tier.NAME_HAS_WORD_FORMS;
		}
		if (!query.text.isEmpty() && hasWholeWords(secondLine(item.getSearchText()), query.text)) {
			return Tier.DESCRIPTION_HAS_WORDS;
		}
		return Tier.DESCRIPTION_OTHER;
	}

	// Whether `words` occurs in `text` with no letter or digit right before or right after it.
	static boolean hasWholeWords(String text, String words) {
		for (int at = text.indexOf(words); at >= 0; at = text.indexOf(words, at + 1)) {
			if ((at == 0 || !isWordChar(text.charAt(at - 1))) && endsWord(text, at + words.length())) {
				return true;
			}
		}
		return false;
	}

	private static boolean endsWord(String text, int end) {
		return end == text.length() || !isWordChar(text.charAt(end));
	}

	// The same split as SearchText's stems: letters and digits make words, everything else splits.
	private static boolean isWordChar(char c) {
		return Character.isLetterOrDigit(c);
	}

	// Item.searchText and searchStems hold the name, then a newline and the description (if any).
	private static String firstLine(String text) {
		int newline = text.indexOf('\n');
		return newline < 0 ? text : text.substring(0, newline);
	}

	private static String secondLine(String text) {
		int newline = text.indexOf('\n');
		return newline < 0 ? "" : text.substring(newline + 1);
	}

	// The search folded once for all items: the text as search_text holds it, the stems as
	// search_stems holds them (" stem1 stem2", null when the search has no words), and the ISBN.
	private record Query(String text, String stems, String isbn) {

		static Query of(ItemFilter filter) {
			List<String> stems = SearchText.stems(filter.text());
			return new Query(SearchText.fold(filter.text()),
					stems.isEmpty() ? null : " " + String.join(" ", stems),
					filter.isbn().map(isbn -> isbn.value()).orElse(null));
		}
	}
}
