package io.github.gallileee.lendloop.item;

// A light stemmer for Czech: cuts the case endings off a word, so "vrtačky", "vrtačkou" and
// "vrtačce" all come down to the same stem as "vrtačka". Based on the light stemmer of Dolamic &
// Savoy, "Indexing and stemming approaches for the Czech language" (Information Processing &
// Management 45(6), 2009), which only removes inflectional suffixes (no derivational ones such as
// "-ník" or "-ost"), so it rarely merges words that mean different things.
//
// Works on folded words (SearchText.fold: lower case, no accents), because that is what both the
// stored item and the search box are. The paper's endings are listed here folded ("-ých" is
// "ych"). Folding loses a few distinctions, e.g. "ů" and "u", so two of the paper's rules are left
// out: "št" -> "sk" (folded "st" is far more often a plain "st") and "ů" -> "o" before the last
// letter. Words of three letters or fewer are left as they are: too little is left to be sure.
//
// Not a dictionary: irregular forms ("pes" / "psa", "člověk" / "lidé") stay apart, and an English
// word gets cut the same way ("game" -> "gam"). Both sides are stemmed alike, so that only matters
// for which words end up equal.
public final class CzechStemmer {

	// Shortest word that is stemmed at all.
	static final int MIN_LENGTH = 4;

	private static final String[] ENDINGS_5 = { "atech" };

	private static final String[] ENDINGS_4 = { "etem", "atum" };

	private static final String[] ENDINGS_3 = { "ech", "ich", "eho", "emi", "emu", "ete", "eti", "iho", "imi", "imu",
			"ach", "ata", "aty", "ych", "ama", "ami", "ove", "ovi", "ymi" };

	private static final String[] ENDINGS_2 = { "em", "es", "im", "um", "at", "am", "os", "us", "ym", "mi", "ou" };

	private static final String VOWELS = "aeiouy";

	private static final String[] POSSESSIVES = { "ov", "in", "uv" };

	private CzechStemmer() {
	}

	// The stem of one folded word (letters only; anything else is returned as it is).
	public static String stem(String word) {
		if (word.length() < MIN_LENGTH || !word.chars().allMatch(Character::isLetter)) {
			return word;
		}
		String stem = removeCase(word);
		stem = removePossessive(stem);
		return normalize(stem);
	}

	// The longest ending that leaves enough of the word, as in the paper: a five-letter ending
	// needs a word longer than 7 letters, four letters longer than 6, and so on.
	private static String removeCase(String word) {
		int length = word.length();
		if (length > 7 && endsWithAny(word, ENDINGS_5)) {
			return word.substring(0, length - 5);
		}
		if (length > 6 && endsWithAny(word, ENDINGS_4)) {
			return word.substring(0, length - 4);
		}
		if (length > 5 && endsWithAny(word, ENDINGS_3)) {
			return word.substring(0, length - 3);
		}
		if (length > 4 && endsWithAny(word, ENDINGS_2)) {
			return word.substring(0, length - 2);
		}
		if (length > 3 && VOWELS.indexOf(word.charAt(length - 1)) >= 0) {
			return word.substring(0, length - 1);
		}
		return word;
	}

	// "Petrova" -> "petrov" -> "petr": the owner's name in a possessive adjective.
	private static String removePossessive(String stem) {
		if (stem.length() > 5 && endsWithAny(stem, POSSESSIVES)) {
			return stem.substring(0, stem.length() - 2);
		}
		return stem;
	}

	// Undoes the sound changes some endings cause, so the stems meet again: "vrtačce" (k -> c),
	// "knize" (h -> z), the paper's "čt" -> "ck", and the "e" that comes and goes before the last
	// letter ("domek" / "domku").
	private static String normalize(String stem) {
		int length = stem.length();
		if (stem.endsWith("ct")) {
			return stem.substring(0, length - 2) + "ck";
		}
		char last = stem.charAt(length - 1);
		if (last == 'c') {
			return stem.substring(0, length - 1) + "k";
		}
		if (last == 'z') {
			return stem.substring(0, length - 1) + "h";
		}
		if (length > 1 && stem.charAt(length - 2) == 'e') {
			return stem.substring(0, length - 2) + last;
		}
		return stem;
	}

	private static boolean endsWithAny(String word, String[] endings) {
		for (String ending : endings) {
			if (word.endsWith(ending)) {
				return true;
			}
		}
		return false;
	}
}
