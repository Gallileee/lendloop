package io.github.gallileee.lendloop.user;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

// Unsubscribe links that work without logging in (RFC 8058 one-click unsubscribe: the mail
// provider POSTs to the link with no cookies). The link carries the user id and the email list,
// signed with HMAC-SHA256, so nobody can make a link for someone else's account.
//
// Nothing is stored: a link stays valid as long as the secret does. That's fine here, because the
// only thing a link can do is turn an email off, which the user can undo in Settings. Changing the
// secret invalidates every link already sent.
@Component
public class UnsubscribeLinks {

	private static final Logger log = LoggerFactory.getLogger(UnsubscribeLinks.class);
	private static final String ALGORITHM = "HmacSHA256";
	static final int MIN_SECRET_LENGTH = 32;

	private final SecretKeySpec key;
	private final String baseUrl;

	public UnsubscribeLinks(@Value("${lendloop.unsubscribe.secret:}") String secret,
			@Value("${lendloop.base-url}") String baseUrl) {
		this.key = new SecretKeySpec(secretBytes(secret), ALGORITHM);
		this.baseUrl = baseUrl.replaceAll("/+$", "");
	}

	// A user id and list that a link was verified for.
	public record Target(Long userId, EmailList list) {
	}

	// The absolute link for an email's footer and List-Unsubscribe header.
	public String urlFor(Long userId, EmailList list) {
		return UriComponentsBuilder.fromUriString(baseUrl)
			.path("/unsubscribe")
			.queryParam("user", userId)
			.queryParam("list", list.name())
			.queryParam("sig", sign(userId, list))
			.build()
			.toUriString();
	}

	// The target if the three parameters are a link this app made; empty for anything else,
	// including missing or malformed parameters.
	public Optional<Target> verify(String user, String list, String sig) {
		if (user == null || list == null || sig == null || !user.matches("\\d{1,18}")) {
			return Optional.empty();
		}
		EmailList parsedList;
		try {
			parsedList = EmailList.valueOf(list);
		}
		catch (IllegalArgumentException e) {
			return Optional.empty();
		}
		Long userId = Long.valueOf(user);
		// Constant-time comparison, so the response time doesn't reveal how much of a guess was right.
		boolean valid = MessageDigest.isEqual(sign(userId, parsedList).getBytes(StandardCharsets.US_ASCII),
				sig.getBytes(StandardCharsets.US_ASCII));
		return valid ? Optional.of(new Target(userId, parsedList)) : Optional.empty();
	}

	private String sign(Long userId, EmailList list) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			// A purpose prefix, so this key's signatures can't be reused for anything else later.
			byte[] signature = mac.doFinal(("unsubscribe:" + userId + ":" + list.name())
				.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException("HmacSHA256 is not available", e);
		}
	}

	// Without a configured secret (local runs), a random one is made at startup: links work until
	// the app restarts. A deployment must set UNSUBSCRIBE_SECRET, or links in sent emails break.
	private static byte[] secretBytes(String secret) {
		if (secret == null || secret.isBlank()) {
			log.warn("lendloop.unsubscribe.secret (UNSUBSCRIBE_SECRET) is not set; using a random one, so "
					+ "unsubscribe links in emails stop working when the app restarts");
			byte[] random = new byte[32];
			new SecureRandom().nextBytes(random);
			return random;
		}
		if (secret.length() < MIN_SECRET_LENGTH) {
			throw new IllegalStateException("lendloop.unsubscribe.secret must be at least " + MIN_SECRET_LENGTH
					+ " characters, e.g. the output of: openssl rand -base64 32");
		}
		return secret.getBytes(StandardCharsets.UTF_8);
	}
}
