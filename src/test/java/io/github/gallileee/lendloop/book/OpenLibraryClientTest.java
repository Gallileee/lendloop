package io.github.gallileee.lendloop.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.gallileee.lendloop.book.BookLookup.BookLookupUnavailableException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// The HTTP side of the lookup against a fake Open Library: which URLs are called, how the
// JSON is read, and what happens when it answers 404, 500 or nonsense.
class OpenLibraryClientTest {

	private static final String BASE = "https://openlibrary.test";
	private static final Isbn FOX = new Isbn("9780140328721");

	MockRestServiceServer server;
	OpenLibraryClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new OpenLibraryClient(builder, BASE, "Lendloop-test");
	}

	private void edition(String json) {
		server.expect(requestTo(BASE + "/isbn/9780140328721.json"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("User-Agent", "Lendloop-test"))
			.andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
	}

	private void author(String id, String name) {
		server.expect(requestTo(BASE + "/authors/" + id + ".json"))
			.andRespond(withSuccess("{\"name\": \"" + name + "\", \"key\": \"/authors/" + id + "\"}",
					MediaType.APPLICATION_JSON));
	}

	@Test
	void readsTheEditionAndItsAuthors() {
		edition("""
				{"title": "Fantastic Mr. Fox", "authors": [{"key": "/authors/OL34184A"}],
				 "publish_date": "October 1, 1988", "publishers": ["Puffin", "Other"],
				 "number_of_pages": 96, "covers": [15152634], "key": "/books/OL7353617M"}""");
		author("OL34184A", "Roald Dahl");

		assertThat(client.find(FOX))
			.hasValue(new BookInfo("Fantastic Mr. Fox", null, List.of("Roald Dahl"), "Puffin", "October 1, 1988"));
		server.verify();
	}

	@Test
	void anUnknownIsbnIsNotFound() {
		server.expect(requestTo(BASE + "/isbn/9780140328721.json")).andRespond(withResourceNotFound());

		assertThat(client.find(FOX)).isEmpty();
	}

	@Test
	void anEditionWithoutATitleIsNotFound() {
		edition("{\"publishers\": [\"Puffin\"]}");

		assertThat(client.find(FOX)).isEmpty();
	}

	@Test
	void aBrokenServiceIsReportedAsUnavailable() {
		server.expect(requestTo(BASE + "/isbn/9780140328721.json")).andRespond(withServerError());

		assertThatExceptionOfType(BookLookupUnavailableException.class).isThrownBy(() -> client.find(FOX));
	}

	@Test
	void aMissingAuthorIsSkippedNotFatal() {
		edition("""
				{"title": "Good Omens", "authors": [{"key": "/authors/OL1A"}, {"key": "/authors/OL2A"}]}""");
		server.expect(requestTo(BASE + "/authors/OL1A.json")).andRespond(withServerError());
		author("OL2A", "Neil Gaiman");

		assertThat(client.find(FOX)).get().extracting(BookInfo::authors).isEqualTo(List.of("Neil Gaiman"));
		server.verify();
	}

	@Test
	void onlyWellFormedAuthorKeysAreFetchedAndAtMostThree() {
		// The odd keys would be put into a URL; they are skipped without a request.
		edition("""
				{"title": "Anthology", "authors": [
				  {"key": "/authors/../../admin"}, {"key": null}, {"key": "/authors/OL1A"},
				  {"key": "/authors/OL2A"}, {"key": "/authors/OL3A"}, {"key": "/authors/OL4A"}]}""");
		// Only the first three entries count, and two of those aren't valid keys. Any other
		// request fails the test: the mock server only answers what is expected.
		author("OL1A", "One");

		assertThat(client.find(FOX)).get().extracting(BookInfo::authors).isEqualTo(List.of("One"));
		server.verify();
	}
}
