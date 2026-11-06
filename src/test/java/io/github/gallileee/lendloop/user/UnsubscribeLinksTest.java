package io.github.gallileee.lendloop.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.util.UriComponentsBuilder;

// The signed links on their own, without Spring.
class UnsubscribeLinksTest {

	private static final String SECRET = "a-secret-that-is-long-enough-0123456789";

	private final UnsubscribeLinks links = new UnsubscribeLinks(SECRET, "https://lendloop.test/");

	private static Map<String, String> params(String url) {
		return UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();
	}

	@Test
	void aLinkFromTheAppIsAcceptedForItsUserAndList() {
		String url = links.urlFor(42L, EmailList.LENT);
		Map<String, String> p = params(url);

		assertThat(url).startsWith("https://lendloop.test/unsubscribe?user=42&list=LENT&sig=");
		assertThat(links.verify(p.get("user"), p.get("list"), p.get("sig")))
			.contains(new UnsubscribeLinks.Target(42L, EmailList.LENT));
	}

	@Test
	void theSignatureCoversTheUserAndTheList() {
		Map<String, String> p = params(links.urlFor(42L, EmailList.LENT));

		assertThat(links.verify("43", "LENT", p.get("sig"))).as("someone else's account").isEmpty();
		assertThat(links.verify("42", "BORROWED", p.get("sig"))).as("the other list").isEmpty();
	}

	@Test
	void aLinkSignedWithAnotherSecretIsRejected() {
		UnsubscribeLinks other = new UnsubscribeLinks("another-secret-that-is-long-enough-012345", "https://x.test");
		Map<String, String> p = params(other.urlFor(42L, EmailList.LENT));

		assertThat(links.verify(p.get("user"), p.get("list"), p.get("sig"))).isEmpty();
	}

	@ParameterizedTest
	@CsvSource(nullValues = "null", value = {
			"null, LENT, abc",
			"42, null, abc",
			"42, LENT, null",
			"abc, LENT, abc",
			"-1, LENT, abc",
			"99999999999999999999, LENT, abc",
			"42, lent, abc",
			"42, SPAM, abc",
			"42, LENT, ''" })
	void malformedParametersAreRejectedWithoutAnError(String user, String list, String sig) {
		assertThat(links.verify(user, list, sig)).isEmpty();
	}

	@Test
	void aTruncatedSignatureIsRejected() {
		String sig = params(links.urlFor(42L, EmailList.LENT)).get("sig");

		assertThat(links.verify("42", "LENT", sig.substring(0, sig.length() - 1))).isEmpty();
	}

	@Test
	void aShortSecretIsRefusedAtStartup() {
		assertThatThrownBy(() -> new UnsubscribeLinks("too short", "https://lendloop.test"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("at least 32 characters");
	}

	@Test
	void withoutASecretLinksStillWorkWithinOneRun() {
		UnsubscribeLinks random = new UnsubscribeLinks("", "https://lendloop.test");
		Map<String, String> p = params(random.urlFor(7L, EmailList.BORROWED));

		assertThat(random.verify(p.get("user"), p.get("list"), p.get("sig"))).isPresent();
		assertThat(new UnsubscribeLinks("", "https://lendloop.test").verify(p.get("user"), p.get("list"), p.get("sig")))
			.as("a restart makes a new secret").isEmpty();
	}
}
