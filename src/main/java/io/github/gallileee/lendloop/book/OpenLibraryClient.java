package io.github.gallileee.lendloop.book;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// Looks books up in Open Library (https://openlibrary.org/dev/docs/api/books), which is free
// and needs no API key:
//   GET /isbn/{isbn}.json      the edition (title, publishers, date, author keys); redirects
//                              to /books/OL…M.json, so redirects are followed
//   GET /authors/OL…A.json     one author's name, for at most MAX_AUTHORS authors
// Edition data (not the "work") is used on purpose: it has the title, publisher and year of
// the copy on the owner's shelf, e.g. a Czech translation rather than the original.
@Component
class OpenLibraryClient implements BookLookup {

	static final int MAX_AUTHORS = 3;

	private static final Logger log = LoggerFactory.getLogger(OpenLibraryClient.class);

	// Author keys come from the response; only this exact shape is put into a URL.
	private static final Pattern AUTHOR_KEY = Pattern.compile("/authors/(OL\\d+A)");

	private final RestClient http;

	@Autowired
	OpenLibraryClient(@Value("${lendloop.openlibrary.base-url}") String baseUrl,
			@Value("${lendloop.openlibrary.user-agent}") String userAgent) {
		this(RestClient.builder().requestFactory(timeoutsAndRedirects()), baseUrl, userAgent);
	}

	// For tests, which bind a MockRestServiceServer to the builder.
	OpenLibraryClient(RestClient.Builder builder, String baseUrl, String userAgent) {
		this.http = builder.baseUrl(baseUrl)
			// Open Library asks regular API users to say who they are in the User-Agent.
			.defaultHeader(HttpHeaders.USER_AGENT, userAgent)
			.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
			.build();
	}

	// The lookup happens while the user waits for the page, so a slow catalogue must not hang it.
	private static JdkClientHttpRequestFactory timeoutsAndRedirects() {
		HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
		factory.setReadTimeout(Duration.ofSeconds(5));
		return factory;
	}

	@Override
	public Optional<BookInfo> find(Isbn isbn) {
		Edition edition;
		try {
			edition = http.get().uri("/isbn/{isbn}.json", isbn.value()).retrieve().body(Edition.class);
		}
		catch (HttpClientErrorException.NotFound e) {
			return Optional.empty();
		}
		catch (RestClientException e) {
			throw new BookLookupUnavailableException("Open Library lookup failed for " + isbn, e);
		}
		if (edition == null || edition.title() == null || edition.title().isBlank()) {
			return Optional.empty();
		}
		String publisher = edition.publishers() == null || edition.publishers().isEmpty() ? null
				: edition.publishers().get(0);
		return Optional.of(new BookInfo(edition.title(), edition.subtitle(), authorNames(edition), publisher,
				edition.publishDate()));
	}

	// Authors are nice to have: if one can't be fetched, the book is still found without it.
	private List<String> authorNames(Edition edition) {
		List<String> names = new ArrayList<>();
		if (edition.authors() == null) {
			return names;
		}
		for (Key author : edition.authors().stream().limit(MAX_AUTHORS).toList()) {
			Matcher key = AUTHOR_KEY.matcher(author.key() == null ? "" : author.key());
			if (!key.matches()) {
				continue;
			}
			try {
				Author found = http.get().uri("/authors/{id}.json", key.group(1)).retrieve().body(Author.class);
				if (found != null && found.name() != null && !found.name().isBlank()) {
					names.add(found.name().strip());
				}
			}
			catch (RestClientException e) {
				log.info("Skipping author {} of an Open Library edition: {}", key.group(1), e.getMessage());
			}
		}
		return names;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Edition(String title, String subtitle, List<Key> authors, List<String> publishers,
			@JsonProperty("publish_date") String publishDate) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Key(String key) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Author(String name) {
	}
}
