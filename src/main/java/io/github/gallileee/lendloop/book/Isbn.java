package io.github.gallileee.lendloop.book;

import java.util.Optional;

// A checked ISBN, always held as ISBN-13 so the same book typed as ISBN-10 or ISBN-13
// (with or without hyphens) ends up as the same value.
public record Isbn(String value) {

	public Isbn {
		if (value == null || !value.matches("97[89]\\d{10}") || checkDigit13(value) != value.charAt(12) - '0') {
			throw new IllegalArgumentException("Not an ISBN-13: " + value);
		}
	}

	// Accepts what people type or scan: ISBN-10 (last digit may be X) or ISBN-13, with spaces
	// or hyphens anywhere. Anything else, including a wrong check digit, gives empty.
	public static Optional<Isbn> parse(String text) {
		if (text == null) {
			return Optional.empty();
		}
		String digits = text.replaceAll("[\\s-]", "").toUpperCase();
		if (digits.matches("\\d{9}[\\dX]")) {
			return isValid10(digits) ? Optional.of(new Isbn(to13(digits))) : Optional.empty();
		}
		if (digits.matches("97[89]\\d{10}") && checkDigit13(digits) == digits.charAt(12) - '0') {
			return Optional.of(new Isbn(digits));
		}
		return Optional.empty();
	}

	// ISBN-10: the digits weighted 10, 9, …, 1 (X = 10, only last) add up to a multiple of 11.
	private static boolean isValid10(String digits) {
		int sum = 0;
		for (int i = 0; i < 10; i++) {
			char c = digits.charAt(i);
			int digit = c == 'X' ? 10 : c - '0';
			sum += digit * (10 - i);
		}
		return sum % 11 == 0;
	}

	// An ISBN-10 becomes an ISBN-13 by putting 978 in front and recomputing the check digit.
	private static String to13(String isbn10) {
		String first12 = "978" + isbn10.substring(0, 9);
		return first12 + checkDigit13(first12);
	}

	// ISBN-13: the first 12 digits weighted 1, 3, 1, 3, …; the check digit tops the sum up to
	// a multiple of 10.
	private static int checkDigit13(String digits) {
		int sum = 0;
		for (int i = 0; i < 12; i++) {
			sum += (digits.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
		}
		return (10 - sum % 10) % 10;
	}

	@Override
	public String toString() {
		return value;
	}
}
