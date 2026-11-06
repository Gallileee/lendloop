package io.github.gallileee.lendloop.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

// The random tokens in emailed one-time links (password reset, email confirmation).
//
// A token is 256 random bits as unpadded base64url (43 characters). The database only keeps its
// SHA-256 hash, so someone who can read the database still can't use a link. A fast hash is
// enough: unlike a password, the token is random and long, so it can't be guessed from its hash.
final class OneTimeTokens {

	private static final SecureRandom RANDOM = new SecureRandom();

	private OneTimeTokens() {
	}

	static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	// What newToken() makes. Anything else can't match a stored hash, so it isn't looked up.
	static boolean isWellFormed(String token) {
		return token != null && token.matches("[A-Za-z0-9_-]{43}");
	}

	static String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}
}
