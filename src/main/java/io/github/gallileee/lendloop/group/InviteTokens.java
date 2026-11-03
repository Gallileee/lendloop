package io.github.gallileee.lendloop.group;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

// Invite tokens: 32 random bytes from SecureRandom (256 bits, impossible to guess),
// Base64url without padding so they fit in a URL as they are (43 characters).
@Component
public class InviteTokens {

	private final SecureRandom random = new SecureRandom();

	public String next() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
